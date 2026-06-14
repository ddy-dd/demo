package com.example.demo.ai.serviceImpl;

import com.example.demo.ai.serviceImpl.service.ChatService;
import com.example.demo.ai.util.SummarizingChatMemoryRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 对话服务实现类
 *
 * 核心职责：
 * 1. 构建 ChatClient 实例（组装 Advisors、Tools、System Prompt）
 * 2. 提供流式对话能力（Flux<ChatResponse>）
 *
 * <h3>架构说明</h3>
 * 使用 Spring AI 的 ChatClient.Builder 组装多层 Advisor：
 * <pre>
 * RetrievalAugmentationAdvisor (RAG 检索增强)
 *   → MessageChatMemoryAdvisor (对话记忆管理)
 *     → SimpleLoggerAdvisor (请求/响应日志)
 *       → DeepSeek ChatModel (大模型)
 * </pre>
 *
 * 对话记忆使用 {@link SummarizingChatMemoryRepository} 包装，
 * 超过 maxMessages 后自动用本地 Ollama 模型生成摘要压缩。
 */
@Service
public class ChatServiceImpl implements ChatService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ChatModel chatModel;

    public ChatServiceImpl(
            ChatClient.Builder chatClientBuilder,
            VectorStore vectorStore,
            ChatModel chatModel,
            @Qualifier("myCustomTools") List<Object> toolBeans) {

        // ── 1. 构建对话记忆系统（支持自动摘要） ─────────────────
        // 底层使用 InMemoryChatMemoryRepository，
        // 用 SummarizingChatMemoryRepository 装饰以实现超长对话自动压缩
        ChatMemoryRepository baseRepository = new InMemoryChatMemoryRepository();
        ChatMemoryRepository summarizingRepo = new SummarizingChatMemoryRepository(baseRepository, 10);
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(summarizingRepo)
                .maxMessages(20)
                .build();

        // ── 2. RAG 检索增强 Advisor ──────────────────────────
        // 从 Milvus 向量库中检索与用户问题最相关的文档片段，
        // 注入到 LLM 上下文中以增强回答准确性
        Advisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .similarityThreshold(0.50)
                        .vectorStore(vectorStore)
                        .build())
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(true) // 允许空上下文，无匹配知识时不影响对话
                        .build())
                .build();

        // ── 3. 对话记忆 Advisor ─────────────────────────────
        var memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();

        // ── 4. 注册自定义 Tool ──────────────────────────────
        List<ToolCallbackProvider> providers = toolBeans.stream()
                .map(bean -> MethodToolCallbackProvider.builder()
                        .toolObjects(bean)
                        .build())
                .collect(Collectors.toList());

        // ── 5. 请求/响应日志 Advisor ─────────────────────────
        SimpleLoggerAdvisor customLogger = new SimpleLoggerAdvisor(
                request -> "请求: " + request.prompt().getUserMessage(),
                response -> "响应: " + response.getResult(),
                0
        );

        // ── 6. 构建 ChatClient ──────────────────────────────
        this.chatClient = chatClientBuilder
                .defaultSystem("你是一个对话智能体，你必须用中文回答，除非用户强制你用英文")
                .defaultAdvisors(retrievalAugmentationAdvisor, memoryAdvisor, customLogger)
                .defaultToolCallbacks(providers.toArray(new ToolCallbackProvider[0]))
                .build();

        this.vectorStore = vectorStore;
        this.chatModel = chatModel;
    }

    /**
     * 获取流式对话响应
     *
     * @param chatId    会话标识（用于记忆隔离）
     * @param userInput 用户输入
     * @return 流式响应，包含 thinking + text 分块
     */
    @Override
    public ChatClient.StreamResponseSpec getStreamResponseSpec(String chatId, String userInput) {
        return this.chatClient.prompt()
                .options(DeepSeekChatOptions.builder().build())
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, chatId))
                .toolContext(Map.of("chatId", chatId))
                .user(userInput)
                .stream();
    }

    /**
     * 仅获取文本内容流（不包含 Tool Calling 元数据）
     */
    @Override
    public Flux<String> generation(String chatId, String userInput) {
        return this.getStreamResponseSpec(chatId, userInput).content();
    }
}
