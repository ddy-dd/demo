package com.example.demo.ai.config;

import com.example.demo.ai.chat.util.SummarizingChatMemoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import reactor.core.publisher.Flux;

import org.springframework.core.Ordered;

import java.util.List;

/**
 * 思维链与缓存前缀 Advisor。
 *
 * <p>Order 设为 {@code Ordered.LOWEST_PRECEDENCE - 100}，放在 advisor 链后端，
 * 确保 memory advisor 和 RAG advisor 先处理完消息，再标记 prefix。</p>
 *
 * <h3>职责</h3>
 * <ol>
 *   <li>在对话历史中的 {@link DeepSeekAssistantMessage} 上设置 {@code prefix: true}，
 *       利用 DeepSeek 原生支持的 prompt caching（prefix 字段）</li>
 *   <li>配合 {@link SummarizingChatMemoryRepository} 的软压缩机制，
 *       在压缩后保留的最近消息上启用前缀缓存，最大化缓存命中率</li>
 * </ol>
 *
 * <h3>工作原理</h3>
 * <p>Spring AI 的 {@code DeepSeekChatModel.createRequest()} 在将消息转为 API 请求时，
 * 会检查 {@code DeepSeekAssistantMessage.prefix} 字段，若为 true 则序列化为
 * {@code "prefix": true}，告知 DeepSeek 将此消息作为缓存前缀。</p>
 *
 * <p>此 Advisor 在 {@code adviseCall()} 和 {@code adviseStream()} 中遍历消息列表，
 * 将 {@code DeepSeekAssistantMessage} 的 prefix 标记设为 true。</p>
 */
@Slf4j
public class ThinkingCacheAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public String getName() {
        return "thinkingCacheAdvisor";
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    /**
     * 非流式调用：在请求发送前标记 assistant 消息的 prefix
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        markAssistantPrefix(request);
        return chain.nextCall(request);
    }

    /**
     * 流式调用：在请求发送前标记 assistant 消息的 prefix
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        markAssistantPrefix(request);
        return chain.nextStream(request);
    }

    /**
     * 遍历 Prompt 中的消息，为 {@link DeepSeekAssistantMessage} 设置 prefix=true。
     *
     * <p>Spring AI 的 {@code DeepSeekChatModel.createRequest()} 在消息转换时会检查
     * {@code assistantMessage instanceof DeepSeekAssistantMessage}，
     * 然后读取 {@code getPrefix()} 值。这里通过 {@code setPrefix(true)} 直接修改
     * 已有消息对象，无需重新创建消息列表。</p>
     */
    private void markAssistantPrefix(ChatClientRequest request) {
        List<Message> messages = request.prompt().getInstructions();
        int marked = 0;
        for (Message msg : messages) {
            if (msg instanceof DeepSeekAssistantMessage dsMsg) {
                dsMsg.setPrefix(true);
                marked++;
            }
        }
        if (marked > 0) {
            log.trace("ThinkingCacheAdvisor: 为 {} 条 assistant 消息设置 prefix=true", marked);
        }
    }
}
