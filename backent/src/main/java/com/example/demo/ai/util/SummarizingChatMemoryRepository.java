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
 * <h3>相比 v1 的改进</h3>
 * <ol>
 *   <li>基于 Token 估算触发压缩，而非消息条数</li>
 *   <li>使用主模型（DeepSeek）做摘要</li>
 *   <li>结构化摘要 Prompt（6 个维度）</li>
 *   <li>压缩冷却期，避免频繁压缩</li>
 *   <li><b>异步压缩</b> —— 模型调用在独立线程池执行，避免和流式回复争抢 HTTP 连接</li>
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

    /** 异步压缩超时（防止后台任务无限卡住） */
    private static final int SUMMARY_TIMEOUT_SECONDS = 60;

    private static final String BOUNDARY_MARKER = "【历史对话摘要 - 以下为之前对话的摘要压缩】";
    private static final String CONTINUATION_MARKER = "请在此基础上继续对话，不需要重复摘要中已涵盖的内容。";

    /**
     * @param delegate       底层仓库
     * @param maxTokens      Token 估算触发阈值
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
     * 保存消息，并在必要时触��异步摘要压缩。
     *
     * <h3>异步设计</h3>
     * 当估算 Token 超限时，不直接在此方法中调用 chatModel.call()（这会导致
     * 死锁：流式对话占用着 HTTP 连接，同步压缩请求永远等不到），而是：
     * <ol>
     *   <li>先把完整历史保存到底层仓库（保证不丢数据）</li>
     *   <li>在独立线程池中执行模型调用</li>
     *   <li>模型调用完成后，用摘要替换仓库中的历史</li>
     * </ol>
     * 如果下一次对话在压缩完成前到来，读到的是完整历史（未压缩但安全）。
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
            boolean overThreshold = estimatedTokens > maxTokens;
            boolean lastIsAssistant = getLastMessageType(uniqueMessagesList);

            // 冷却计数
            int roundsSinceLastCompact = compressionCounters.getOrDefault(conversationId, 0);
            boolean cooldownPassed = roundsSinceLastCompact >= cooldownRounds;

            // === 触发异步压缩 ===
            if (overThreshold && lastIsAssistant && cooldownPassed) {
                log.info("对话 [{}] 触发压缩: 估算 {} tokens (阈值 {}), {} 条消息",
                        conversationId, estimatedTokens, maxTokens, uniqueMessagesList.size());

                // 先保存完整历史（不丢数据）
                delegate.saveAll(conversationId, uniqueMessagesList);

                // 异步执行模型调用（独立线程，不阻塞当前线程）
                triggerAsyncCompression(conversationId, uniqueMessagesList);

                // 重置冷却
                compressionCounters.put(conversationId, 0);

            } else {
                // 未触发压缩，递增冷却计数器
                if (lastIsAssistant) {
                    compressionCounters.put(conversationId, roundsSinceLastCompact + 1);
                }

                if (overThreshold && !lastIsAssistant) {
                    log.debug("对话 [{}] 超限但等待 AI 回复再压缩: {} tokens", conversationId, estimatedTokens);
                } else if (overThreshold && !cooldownPassed) {
                    log.debug("对话 [{}] 超限但处于冷却期 ({}/{} 轮): {} tokens",
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
     * 模型调用在独立线程执行，即使 HTTP 连接池暂时被流式回复占用，
     * 也不会阻塞主线程。压缩完成后用摘要替换仓库中的完整历史。
     */
    private void triggerAsyncCompression(String conversationId, List<Message> messages) {
        CompletableFuture.runAsync(() -> {
            try {
                log.debug("异步压缩开始: 对话 [{}]", conversationId);

                // chatModel 的同步 call 在后台线程池中执行，
                // 不阻塞主对话线程。如果连接池满了也只是这个后台线程等，不影响用户。
                String summary = chatModel.call(buildSummaryPrompt(messages));
                String cleaned = cleanSummary(summary);

                // 压缩完成，替换历史
                List<Message> compressed = Collections.singletonList(
                        new SystemMessage(BOUNDARY_MARKER + "\n\n" + cleaned + "\n\n" + CONTINUATION_MARKER)
                );

                // 用锁保护写入，防止和正�的 saveAll 竞争
                ReentrantLock lock = locks.computeIfAbsent(conversationId, k -> new ReentrantLock());
                lock.lock();
                try {
                    delegate.saveAll(conversationId, compressed);
                    log.info("对话 [{}] 异步压缩完成: {} 条消息 → 1 条摘要",
                            conversationId, messages.size());
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
