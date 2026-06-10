package com.example.demo.AI.util;

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

@Slf4j
public class SummarizingChatMemoryRepository implements ChatMemoryRepository {

    private final ChatMemoryRepository delegate;
    private final int maxMessages;
    private final ChatModel chatModel;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    //private static ChatModel chatModel;

    //public SummarizingChatMemoryRepository(ChatMemoryRepository delegate, int maxMessages, ChatModel chatModel) {
    public SummarizingChatMemoryRepository(ChatMemoryRepository delegate, int maxMessages) {
        this.delegate = delegate;
        this.maxMessages = maxMessages;
        this.chatModel = this.createChatModel();
    }

    private OllamaChatModel createChatModel() {
        return OllamaChatModel.builder()
                .ollamaApi(OllamaApi.builder().build())
                .defaultOptions(OllamaChatOptions.builder().model("qwen3.5:0.8b").disableThinking().temperature(0.9).build())
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

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        // 1. 获取当前存储中的所有历史记录
        ReentrantLock lock = locks.computeIfAbsent(conversationId, k -> new ReentrantLock());
        lock.lock();
        try {
            List<Message> currentHistory = delegate.findByConversationId(conversationId);

            // 2. 合并：旧历史 + 新消息
            List<Message> combinedHistory = new ArrayList<>(currentHistory);
            combinedHistory.addAll(messages);

            // 3. 去重
            Set<Message> uniqueMessages = new LinkedHashSet<>(combinedHistory);
            List<Message> uniqueMessagesList = new ArrayList<>(uniqueMessages);
            // 4. 判断合并后的总长度是否超过限制
            if ("ASSISTANT".equals(uniqueMessagesList.getLast().getMessageType().name())
                    && uniqueMessagesList.size() > maxMessages) {
                log.warn(uniqueMessagesList.toString());
                // 3.1 调用模型生成摘要
                String summaryPrompt = "请对以下对话历史进行简明扼要的总结，保留关键信息和用户意图：\n"
                        + formatMessages(uniqueMessagesList);
                String summary = chatModel.call(summaryPrompt);
                log.info("生成历史对话摘要: {}", summary);

                // 3.2 构建新的历史列表：只包含一个 SystemMessage
                List<Message> newHistory = Collections.singletonList(
                        new SystemMessage("历史对话摘要: " + summary)
                );

                // 3.3 保存（覆盖）新列表
                delegate.saveAll(conversationId, newHistory);
            } else {
                // 4. 如果没超限，直接保存合并后的完整列表
                delegate.saveAll(conversationId, uniqueMessagesList); // <--- 注意：这里要保存合并后的，而不是仅 messages
            }
        }finally {
            lock.unlock();
            locks.remove(conversationId, lock);
        }

    }

    @Override
    public void deleteByConversationId(String conversationId) {

    }

    private String formatMessages(List<Message> messages) {
        StringBuilder formatted = new StringBuilder();

        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            String role = getRoleLabel(message);
            String content = message.getText();

            formatted.append(String.format("[%d] %s: %s\n",
                    i + 1,
                    role,
                    content.trim()));
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
