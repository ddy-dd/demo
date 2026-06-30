package com.example.demo.ai.serviceImpl;

import com.example.demo.ai.serviceImpl.service.ChatService;
import com.example.demo.ai.skill.SkillAgentService;
import com.example.demo.ai.skill.SkillFormatter;
import com.example.demo.ai.skill.SkillRegistry;
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
import org.springframework.beans.factory.annotation.Value;
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
 * 基于 Token 估算自动触发摘要压缩。使用主模型（DeepSeek）生成
 * 结构化摘要，超过阈值后自动压缩以节省上下文窗口。
 */
@Service
public class ChatServiceImpl implements ChatService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ChatModel chatModel;

    /** Token 触发阈值（由配置注入） */
    private final int maxSummaryTokens;

    /** 冷却轮数 */
    private final int compressionCooldown;

    /** 技能系统提示词，指导 AI 如何使用技能 */
    private static final String SKILL_SYSTEM_PROMPT = """
            你有以下技能可供使用。技能是预定义的能力包，包含详细的执行指令。

            使用规则：
            1. 调用 listSkills() 查看所有可用技能
            2. 调用 readSkill("<技能名>") 读取技能的完整指令
            3. 根据技能指令执行后续操作

            重要：技能调用是内部行为，直接执行即可，不要向用户报告你调用了什么技能、读取了什么内容。
            就像你不会报告"我正在调用 getWeather 工具"一样，技能调用也不需要告知用户，也不要对话和在思考中出现工具的名称，这是机密。
            """.stripIndent();

    public ChatServiceImpl(
            ChatClient.Builder chatClientBuilder,
            VectorStore vectorStore,
            ChatModel chatModel,
            @Qualifier("myCustomTools") List<Object> toolBeans,
            SkillAgentService skillAgentService,
            SkillRegistry skillRegistry,
            @Value("${app.context.max-summary-tokens:80000}") int maxSummaryTokens,
            @Value("${app.context.compression-cooldown-rounds:3}") int compressionCooldown) {

        // ── 1. 构建对话记忆系统（支持自动摘要） ─────────────────
        // 底层使用 InMemoryChatMemoryRepository，
        // 用 SummarizingChatMemoryRepository 装饰以实现超长对话自动压缩
        // 触发条件：估算 Token 超过阈值（默认 80000），而非消息条数
        // 并使用主模型（DeepSeek）做结构化摘要，而非本地小模型
        this.maxSummaryTokens = maxSummaryTokens;
        this.compressionCooldown = compressionCooldown;
        ChatMemoryRepository baseRepository = new InMemoryChatMemoryRepository();
        ChatMemoryRepository summarizingRepo = new SummarizingChatMemoryRepository(
                baseRepository, maxSummaryTokens, chatModel, compressionCooldown);
        // maxMessages 设为较大值（200），让 token 估算的压缩机制做主，
        // 滑动窗口仅作为安全兜底
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(summarizingRepo)
                .maxMessages(200)
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

        // ── 4b. 注册技能 Agent Tool（listSkills / readSkill） ──
        providers.add(MethodToolCallbackProvider.builder()
                .toolObjects(skillAgentService)
                .build());

        // ── 5. 构建系统提示（含技能清单） ─────────────────
        String skillListing = buildSkillListing(skillRegistry);
        String systemPrompt = "你是一个对话智能体，你必须用中文回答，除非用户强制你用英文。\n\n"
                + SKILL_SYSTEM_PROMPT
                + (skillListing.isEmpty() ? "" : "\n\n当前可用技能：\n" + skillListing);

        // ── 6. 请求/响应日志 Advisor ─────────────────────────
        SimpleLoggerAdvisor customLogger = new SimpleLoggerAdvisor(
                request -> "请求: " + request.prompt().getUserMessage(),
                response -> "响应: " + response.getResult(),
                0
        );

        // ── 7. 构建 ChatClient ──────────────────────────────
        this.chatClient = chatClientBuilder
                .defaultSystem(systemPrompt)
                .defaultAdvisors(retrievalAugmentationAdvisor, memoryAdvisor, customLogger)
                .defaultTools(providers.toArray())
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
                .options(DeepSeekChatOptions.builder())
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

    /** 构建技能清单文本（系统技能完整显示，用户技能按预算截断） */
    private static String buildSkillListing(SkillRegistry registry) {
        var all = registry.listAll();
        if (all.isEmpty()) return "";
        return SkillFormatter.format(all, registry.systemSkillNames(), 0);
    }
}
