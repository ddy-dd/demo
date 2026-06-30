package com.example.demo.ai.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 对话记忆摘要仓库 —— 装饰器模式
 *
 * <h3>核心逻辑</h3>
 * 包装底层的 ChatMemoryRepository，在对话 Token 估算超过阈值时自动调用
 * 主模型（DeepSeek）生成结构化摘要，用摘要替换完整历史以节省上下文窗口。
 *
 * <h3>缓存前缀保护（v2 改进）</h3>
 * 引入两级阈值（软/硬），在 "上下文较大但还没到必须压缩" 的阶段
 * 保持请求前缀不变，让 API 侧 Prompt Caching 保持高命中率。
 *
 * <ol>
 *   <li><b>软阈值（SOFT_RATIO=80%）</b> — 只记录日志提示，不压缩。</li>
 *   <li><b>硬阈值（HARD_RATIO=100%）</b> — 触发实际压缩。</li>
 *   <li><b>保留最近消息尾部</b> — 压缩时保留最近 N 条原始消息在摘要之后，
 *       使压缩后和压缩前的请求前缀部分一致，避免缓存完全归零。</li>
 * </ol>
 */
@Slf4j
public class SummarizingChatMemoryRepository implements ChatMemoryRepository {

    private final ChatMemoryRepository delegate;
    private final int maxTokens;
    private final ChatModel chatModel;
    private final int cooldownRounds;

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> compressionCounters = new ConcurrentHashMap<>();

    /**
     * 异步压缩线程池。
     * 压缩任务在独立线程执行，不会阻塞主对话的流式回复。
     * 单线程足以应对（同时只有一个压缩任务需要运行）。
     */
    private final ExecutorService summaryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "summary-worker");
        t.setDaemon(true);
        return t;
    });

    /** 字符/Token 估算比率：中文约 2 字符/Token，保守设置 */
    private static final double CHARS_PER_TOKEN = 2.0;

    /** 缓存安全余量 10% */
    private static final double TOKEN_SAFETY_MARGIN = 1.1;

    /**
     * 软/硬压缩比率（相对于 maxTokens）。
     *
     * 设计理由：
     * - softRatio=80% 时发通知但不压缩 → 请求前缀稳定 → API 缓存保持命中
     * - hardRatio=100% 时压缩 → 释放上下文窗口
     * - 两者之间约 20% 的空间是"缓存保护区"：上下文在增长但缓存不受影响
     */
    public static final double SOFT_RATIO = 0.80;
    public static final double HARD_RATIO = 1.00;

    /** 压缩后保留的最近原始消息数。保留尾部让压缩前后的请求前缀部分一致。 */
    private static final int RECENT_KEEP = 5;

    /**
     * 每对话的软通知状态。
     * 防止软阈值区间多次触发重复日志。
     * 当上下文回落到软阈值以下时自动复位。
     */
    private final ConcurrentHashMap<String, Boolean> softNoticed = new ConcurrentHashMap<>();

    /** 异步压缩超时（防止后台任务无限卡住） */
    private static final int SUMMARY_TIMEOUT_SECONDS = 60;

    private static final String BOUNDARY_MARKER = "【历史对话摘要 - 以下为之前对话的摘要压缩】";
    private static final String CONTINUATION_MARKER = "请在此基础上继续对话，不需要重复摘要中已涵盖的内容。";

    /**
     * @param delegate       底层仓库
     * @param maxTokens      Token 估算触发硬压缩的阈值
     * @param chatModel      用于生成摘要的主模型
     * @param cooldownRounds 两次压缩之间的最少对话轮数
     */
    public SummarizingChatMemoryRepository(ChatMemoryRepository delegate,
                                           int maxTokens,
                                           ChatModel chatModel,
                                           int cooldownRounds) {
        this.delegate = delegate;
        this.maxTokens = maxTokens;
        this.chatModel = chatModel;
        this.cooldownRounds = cooldownRounds;
    }

    @Override
    public List<String> findConversationIds() {
        return delegate.findConversationIds();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return delegate.findByConversationId(conversationId);
    }

    /**
     * 保存消息，三级决策：
     *
     * <ol>
     *   <li><b>正常区（&lt; SOFT_RATIO）</b>：不做任何特殊操作</li>
     *   <li><b>软警告区（SOFT_RATIO ~ HARD_RATIO）</b>：记录日志，不压缩，
     *       保持请求前缀不变以保护 API 缓存命中率</li>
     *   <li><b>硬压缩区（&ge; HARD_RATIO）</b>：触发异步压缩，压缩后保留
     *       最近 RECENT_KEEP 条原始消息在摘要之后，让压缩前后的前缀部分一致</li>
     * </ol>
     */
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        ReentrantLock lock = locks.computeIfAbsent(conversationId, k -> new ReentrantLock());
        lock.lock();
        try {
            List<Message> currentHistory = delegate.findByConversationId(conversationId);
            List<Message> combinedHistory = new ArrayList<>(currentHistory);
            combinedHistory.addAll(messages);

            // 去重
            Set<Message> uniqueMessages = new LinkedHashSet<>(combinedHistory);
            List<Message> uniqueMessagesList = new ArrayList<>(uniqueMessages);

            if (uniqueMessagesList.isEmpty()) {
                return;
            }

            int estimatedTokens = estimateTokens(uniqueMessagesList);
            double usageRatio = (double) estimatedTokens / maxTokens;
            boolean lastIsAssistant = getLastMessageType(uniqueMessagesList);

            // 冷却计数
            int roundsSinceLastCompact = compressionCounters.getOrDefault(conversationId, 0);
            boolean cooldownPassed = roundsSinceLastCompact >= cooldownRounds;

            // ── 三级决策 ───────────────────────────────────────────────

            if (usageRatio >= HARD_RATIO && lastIsAssistant && cooldownPassed) {
                // ── ③ 硬压缩区：超过阈值，触发异步压缩 ──────────
                log.info("对话 [{}] 触发硬压缩: 估算 {} tokens (阈值 {}), {} 条消息",
                        conversationId, estimatedTokens, maxTokens, uniqueMessagesList.size());

                softNoticed.remove(conversationId);

                // 先保存完整历史（不丢数据）
                delegate.saveAll(conversationId, uniqueMessagesList);

                // 异步执行模型调用
                triggerAsyncCompression(conversationId, uniqueMessagesList);

                // 重置冷却
                compressionCounters.put(conversationId, 0);

            } else if (usageRatio >= SOFT_RATIO && lastIsAssistant) {
                // ── ② 软警告区：只记录日志，不压缩 ─────────────
                // 保持请求前缀不变 → API 侧 Prompt Caching 继续命中
                boolean alreadyNoticed = softNoticed.getOrDefault(conversationId, false);
                if (!alreadyNoticed) {
                    int softPct = (int) (SOFT_RATIO * 100);
                    int hardPct = (int) (HARD_RATIO * 100);
                    int curPct = (int) (usageRatio * 100);
                    log.info("对话 [{}] 上下文使用 {}%（软阈值 {}%，硬阈值 {}%），"
                                    + "当前保持缓存前缀稳定，将在 {}% 时压缩",
                            conversationId, curPct, softPct, hardPct, hardPct);
                    softNoticed.put(conversationId, true);
                }

                if (lastIsAssistant) {
                    compressionCounters.put(conversationId, roundsSinceLastCompact + 1);
                }

                delegate.saveAll(conversationId, uniqueMessagesList);

            } else {
                // ── ① 正常区：未超阈值 ─────────────────────────
                if (lastIsAssistant) {
                    compressionCounters.put(conversationId, roundsSinceLastCompact + 1);
                }

                // 如果上下文回落到软阈值以下，复位软通知以便下次重新警告
                if (usageRatio < SOFT_RATIO) {
                    softNoticed.remove(conversationId);
                }

                if (usageRatio >= HARD_RATIO && !lastIsAssistant) {
                    log.debug("对话 [{}] 超限但等待 AI 回复再压缩: {} tokens",
                            conversationId, estimatedTokens);
                } else if (usageRatio >= HARD_RATIO && !cooldownPassed) {
                    log.debug("对话 [{}] 超限但处于冷却期 ({}/{}) 轮: {} tokens",
                            conversationId, roundsSinceLastCompact, cooldownRounds, estimatedTokens);
                }

                delegate.saveAll(conversationId, uniqueMessagesList);
            }

        } finally {
            lock.unlock();
            locks.remove(conversationId, lock);
        }
    }

    /**
     * 在后台线程池中执行摘要压缩。
     *
     * 相较于 v1 的改动：
     * <ol>
     *   <li>将消息拆分为"折叠区"（旧消息，用于生成摘要）和"保留区"（最近消息，保持原样）</li>
     *   <li>压缩结果 = [摘要] + [最近 RECENT_KEEP 条保留消息]</li>
     *   <li>这样压缩后下一条请求的前缀和压缩前部分一致，API 缓存不会完全归零</li>
     * </ol>
     */
    private void triggerAsyncCompression(String conversationId, List<Message> messages) {
        CompletableFuture.runAsync(() -> {
            try {
                log.debug("异步压缩开始: 对话 [{}]", conversationId);

                // ── 拆分折叠区与保留区 ─────────────────────────
                // 折叠区 → 生成摘要；保留区 → 保持原样拼在摘要后面
                int keepStart;
                if (messages.size() > RECENT_KEEP) {
                    keepStart = messages.size() - RECENT_KEEP;
                } else {
                    // 消息太少没什么好保留下来的，直接全部压缩
                    keepStart = 0;
                }
                List<Message> foldMessages = messages.subList(0, keepStart);
                List<Message> keepMessages = messages.subList(keepStart, messages.size());

                // 如果折叠区为空，不需要调用模型
                if (foldMessages.isEmpty()) {
                    log.warn("对话 [{}] 压缩跳过：折叠区为空（总消息数 {} ≤ 保留数 {})",
                            conversationId, messages.size(), RECENT_KEEP);
                    return;
                }

                // chatModel 的同步 call 在后台线程池中执行，
                // 不阻塞主对话线程。如果连接池满了也只是这个后台线程等，不影响用户。
                String summary = chatModel.call(buildSummaryPrompt(foldMessages));
                String cleaned = cleanSummary(summary);

                // ── 构建压缩结果 ───────────────────────────────
                // 结构：[摘要 SystemMessage] + [最近保留的原始消息]
                // 这样下一条请求中，"摘要 + 最近 N 条" 作为前缀，
                // 和压缩前的请求前缀有大量重叠字节 → API 缓存保持命中
                List<Message> compressed = new ArrayList<>(keepMessages.size() + 1);
                compressed.add(new SystemMessage(
                        BOUNDARY_MARKER + "\n\n" + cleaned + "\n\n" + CONTINUATION_MARKER));
                compressed.addAll(keepMessages);

                // 用锁保护写入，防止和并发的 saveAll 竞争
                ReentrantLock lock = locks.computeIfAbsent(conversationId, k -> new ReentrantLock());
                lock.lock();
                try {
                    // 先清理旧数据再写入
                    delegate.deleteByConversationId(conversationId);
                    delegate.saveAll(conversationId, compressed);
                    log.info("对话 [{}] 异步压缩完成: {} 条折叠 + {} 条保留 → {} 条摘要 + {} 条保留",
                            conversationId,
                            foldMessages.size(), keepMessages.size(),
                            1, keepMessages.size());
                } finally {
                    lock.unlock();
                    locks.remove(conversationId, lock);
                }

            } catch (Exception e) {
                log.error("异步压缩失败: 对话 [{}]", conversationId, e);
            }
        }, summaryExecutor)
        .orTimeout(SUMMARY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .exceptionally(ex -> {
            log.warn("异步压缩超时或异常: 对话 [{}] - {}", conversationId, ex.getMessage());
            return null;
        });
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        compressionCounters.remove(conversationId);
        softNoticed.remove(conversationId);
        delegate.deleteByConversationId(conversationId);
    }

    // ========================================================================
    // Token 估算
    // ========================================================================

    private int estimateTokens(List<Message> messages) {
        int totalChars = 0;
        for (Message message : messages) {
            String text = message.getText();
            if (text != null) {
                totalChars += text.length();
            }
            totalChars += 6; // 角色标签开销
        }
        return (int) Math.ceil(totalChars / CHARS_PER_TOKEN * TOKEN_SAFETY_MARGIN);
    }

    // ========================================================================
    // 摘要 Prompt
    // ========================================================================

    private String buildSummaryPrompt(List<Message> messages) {
        return """
                你是一个对话摘要助手。请对以下对话进行详细的结构化摘要。

                注意：
                - 直接输出摘要内容，不要输出思考过程
                - 用中文回答
                - 以 "【对话摘要】" 开头
                - 保留所有关键细节，不要遗漏

                请按以下结构组织：

                1. 对话主题和用户意图：用户在聊什么？想达到什么目的？
                2. 关键讨论点和信息交换：用户提供了哪些重要信息？助手给出了哪些重要回答？
                3. 调用的工具和结果：是否有查询时间、天气、位置等操作？结果是什么？
                4. 用户表达的偏好和反馈：用户对回复是否满意？对风格、详细程度有无要求？
                5. 待办和未完成请求：是否有还在进行中的任务或用户等待的信息？
                6. 当前状态：对话进行到哪一步了？有没有需要记住的重要上下文？

                对话内容：
                """ + formatMessages(messages);
    }

    private String cleanSummary(String raw) {
        if (raw == null || raw.isBlank()) {
            return "（摘要生成失败）";
        }
        if (raw.contains("<summary>")) {
            int start = raw.indexOf("<summary>") + "<summary>".length();
            int end = raw.lastIndexOf("</summary>");
            if (end > start) {
                return raw.substring(start, end).trim();
            }
        }
        if (raw.contains("【对话摘要】")) {
            int start = raw.indexOf("【对话摘要】");
            return raw.substring(start).trim();
        }
        return raw.trim();
    }

    // ========================================================================
    // 格式化工具
    // ========================================================================

    private String formatMessages(List<Message> messages) {
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            String role = getRoleLabel(message);
            String content = message.getText();
            formatted.append(String.format("[%d] %s: %s\n", i + 1, role, content != null ? content.trim() : ""));
        }
        return formatted.toString().trim();
    }

    private String getRoleLabel(Message message) {
        if (message instanceof UserMessage) return "用户";
        else if (message instanceof AssistantMessage) return "助手";
        else if (message instanceof SystemMessage) return "系统";
        return "未知";
    }

    /** 安全获取最后一条消息的类型，空列表返回 false */
    private boolean getLastMessageType(List<Message> messages) {
        if (messages.isEmpty()) return false;
        Message last = messages.getLast();
        if (last == null) return false;
        String type = last.getMessageType().name();
        return "ASSISTANT".equals(type);
    }
}
