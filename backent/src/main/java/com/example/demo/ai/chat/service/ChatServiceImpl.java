package com.example.demo.ai.chat.service;

import com.example.demo.ai.chat.util.SummarizingChatMemoryRepository;
import com.example.demo.ai.config.ThinkingCacheAdvisor;
import com.example.demo.ai.skills.service.SkillAgentService;
import com.example.demo.ai.skills.util.SkillFormatter;
import com.example.demo.ai.skills.service.SkillRegistry;
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
            能力概览
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            你有以下三大类能力，按使用频率排列：

            【1. 知识库 — 小说创作、记忆、资料查询】
            适用场景：读取已导入的小说、查询知识图谱、RAG 检索
            对应工具：vectorStore 检索（自动）、novelbase MCP 工具
            使用方式：对话中自然触发，无需手动操作

            【2. 文件/文档处理】
            适用场景：读取小说文件、导入结构化文档、写 .md 文件
            对应工具：NovelReader, ImportDocument, FileWriter

            【3. 实用工具】
            适用场景：查时间、查天气、查位置
            对应工具：GetTime, GetWeather, GetUserLocation

            ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─
            技能系统使用规则：
            当前已列出所有可用技能的 name 和 description。
            如果你觉得某个技能适用于当前任务，调用
            readSkill("<技能名>") 读取完整指令，然后执行。

            重要：工具调用是内部行为，直接执行即可，
            不要向用户报告你调用了什么工具、读取了什么内容。
            """.stripIndent();

    public ChatServiceImpl(
            ChatClient.Builder chatClientBuilder,
            VectorStore vectorStore,
            ChatModel chatModel,
            @Qualifier("myCustomTools") List<Object> toolBeans,
            SkillAgentService skillAgentService,
            SkillRegistry skillRegistry,
            @Value("${app.context.max-summary-tokens:80000}") int maxSummaryTokens,
            @Value("${app.context.compression-cooldown-rounds:3}") int compressionCooldown,
            List<ToolCallbackProvider> mcpToolProviders) {

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

        // ── 4b. 注册 readSkill Tool ──
        providers.add(MethodToolCallbackProvider.builder()
                .toolObjects(skillAgentService)
                .build());

        // ── 4c. MCP 工具（novelbase） ──
        providers.addAll(mcpToolProviders);

        // ── 5. 构建系统提示（含技能清单） ─────────────────
        String skillListing = buildSkillListing(skillRegistry);
        String systemPrompt = "你是一个对话智能体，你必须用中文回答，除非用户强制你用英文。\n\n"
                + SKILL_SYSTEM_PROMPT
                + (skillListing.isEmpty() ? "" : "\n\n当前可用技能：\n" + skillListing);

        // ── 6. 缓存前缀 Advisor（给 assistant 消息标记 prefix=true） ──
        var cacheAdvisor = new ThinkingCacheAdvisor();

        // ── 7. 请求/响应日志 Advisor ─────────────────────────
        SimpleLoggerAdvisor customLogger = new SimpleLoggerAdvisor(
                request -> "请求: " + request.prompt().getUserMessage(),
                response -> "响应: " + response.getResult(),
                0
        );

        // ── 8. 构建 ChatClient ──────────────────────────────
        this.chatClient = chatClientBuilder
                .defaultSystem(systemPrompt)
                .defaultAdvisors(retrievalAugmentationAdvisor, memoryAdvisor, cacheAdvisor, customLogger)
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
