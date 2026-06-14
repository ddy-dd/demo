package com.example.demo.ai.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 对话记忆摘要仓库 —— 装饰器模式
 *
 * 包装底层的 ChatMemoryRepository，在对话消息数量超过阈值时自动调用
 * 本地 Ollama 小模型生成摘要，用摘要替换完整历史。
 *
 * <h3>为什么需要这个？</h3>
 * 如果不做摘要压缩，超长对话会不断消耗 LLM 上下文窗口（Token 数），
 * 既浪费 Token 又影响回复质量。用更便宜的本地小模型做"记忆压缩"，
 * 保留关键信息，丢弃冗余细节。
 *
 * <h3>触发条件</h3>
 * 当一次 saveAll 后消息总数超过 maxMessages，
 * 且最后一条消息来自 AI（一轮对话完成），触发摘要。
 */
@Slf4j
public class SummarizingChatMemoryRepository implements ChatMemoryRepository {

    private final ChatMemoryRepository delegate;
    private final int maxMessages;
    private final ChatModel chatModel;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public SummarizingChatMemoryRepository(ChatMemoryRepository delegate, int maxMessages) {
        this.delegate = delegate;
        this.maxMessages = maxMessages;
        this.chatModel = this.createChatModel();
    }

    /** 创建本地 Ollama 模型实例，用于对话摘要 */
    private OllamaChatModel createChatModel() {
        return OllamaChatModel.builder()
                .ollamaApi(OllamaApi.builder().build())
                .defaultOptions(OllamaChatOptions.builder()
                        .model("qwen3.5:0.8b")
                        .disableThinking()
                        .temperature(0.9)
                        .build())
                .build();
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
     * 保存消息，并在必要时触发摘要压缩
     *
     * 设计要点：
     * - 使用 per-conversation 的 ReentrantLock 保证线程安全
     * - 合并旧历史 + 新消息后去重（避免 Spring AI 的内部重复追加）
     * - 仅在 "AI 完成了一轮回复" 时才触发摘要（避免在用户连续输入时频繁压缩）
     * - 压缩后仅保留一个 SystemMessage（摘要内容），大幅减少 Token 消耗
     */
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        ReentrantLock lock = locks.computeIfAbsent(conversationId, k -> new ReentrantLock());
        lock.lock();
        try {
            // 合并旧历史 + 新消息
            List<Message> currentHistory = delegate.findByConversationId(conversationId);
            List<Message> combinedHistory = new ArrayList<>(currentHistory);
            combinedHistory.addAll(messages);

            // 去重（LinkedHashSet 保留插入顺序）
            Set<Message> uniqueMessages = new LinkedHashSet<>(combinedHistory);
            List<Message> uniqueMessagesList = new ArrayList<>(uniqueMessages);

            // 判断是否需要触发摘要：
            // 条件1：消息总数超过阈值
            // 条件2：最后一条消息来自 AI（一轮对话完成）
            if (uniqueMessagesList.size() > maxMessages
                    && "ASSISTANT".equals(uniqueMessagesList.getLast().getMessageType().name())) {

                // 用本地 Ollama 模型生成摘要
                String summaryPrompt = "请对以下对话历史进行简明扼要的总结，保留关键信息和用户意图：\n"
                        + formatMessages(uniqueMessagesList);
                String summary = chatModel.call(summaryPrompt);
                log.info("对话 [{}] 已压缩: {} 条消息 → 1 条摘要", conversationId, uniqueMessagesList.size());

                // 仅保留摘要作为 SystemMessage
                List<Message> newHistory = Collections.singletonList(
                        new SystemMessage("历史对话摘要: " + summary)
                );
                delegate.saveAll(conversationId, newHistory);
            } else {
                // 未超限，直接保存合并后的完整列表
                delegate.saveAll(conversationId, uniqueMessagesList);
            }

        } finally {
            lock.unlock();
            locks.remove(conversationId, lock);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        delegate.deleteByConversationId(conversationId);
    }

    /** 将消息列表格式化为摘要提示词 */
    private String formatMessages(List<Message> messages) {
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            String role = getRoleLabel(message);
            String content = message.getText();
            formatted.append(String.format("[%d] %s: %s\n", i + 1, role, content.trim()));
        }
        return formatted.toString().trim();
    }

    private String getRoleLabel(Message message) {
        if (message instanceof UserMessage) return "用户";
        else if (message instanceof AssistantMessage) return "助手";
        else if (message instanceof SystemMessage) return "系统";
        return "未知";
    }
}
