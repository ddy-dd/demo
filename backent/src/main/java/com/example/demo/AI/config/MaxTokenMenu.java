package com.example.demo.ai.config;

import com.example.demo.ai.chat.util.SummarizingChatMemoryRepository;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token 上下文预算菜单。
 *
 * <h3>职责</h3>
 * 统一管理不同模型的最大上下文窗口和不同场景的 Token 预算，
 * 核心规则：<b>effectiveMax = min(modelMaxWindow, scenarioBudget)</b>
 *
 * <h3>使用场景</h3>
 * <ol>
 *   <li>为 {@code SummarizingChatMemoryRepository} 提供压缩触发阈值
 *       （effectiveMax × HARD_RATIO）</li>
 *   <li>控制输出的 max_tokens 参数</li>
 *   <li>切换模型或场景时自动调整上下文预算</li>
 * </ol>
 *
 * <h3>模型匹配规则</h3>
 * <ol>
 *   <li>精确匹配全名（如 {@code deepseek-v4-flash}）</li>
 *   <li>前缀匹配（如 {@code qwen} 匹配 {@code qwen3.5:0.8b}）</li>
 *   <li>未知模型 → 默认 128K</li>
 * </ol>
 */
@Slf4j
@Component
public class MaxTokenMenu {

    /** 未知模型的默认窗口 */
    public static final int DEFAULT_WINDOW = 128 * 1024; // 128K

    /** 聊天场景默认预算 */
    public static final int DEFAULT_CHAT_BUDGET = 64 * 1024; // 64K

    // ─────────────────────────────────────────────────────────────────
    // 模型 → 最大上下文窗口
    // 有序：精确匹配优先，前缀匹配按插入顺序
    // ─────────────────────────────────────────────────────────────────
    private static final Map<String, Integer> MODEL_WINDOWS;

    // ─────────────────────────────────────────────────────────────────
    // 场景 → Token 预算
    // ─────────────────────────────────────────────────────────────────
    private static final Map<Scenario, Integer> SCENARIO_BUDGETS;

    static {
        // ── 初始化模型窗口 ──────────────────────────────────────
        Map<String, Integer> models = new LinkedHashMap<>();

        // DeepSeek 系列
        models.put("deepseek-v4-flash", 128 * 1024);   // 当前使用
        models.put("deepseek-chat", 128 * 1024);        // V3 / V4
        models.put("deepseek-reasoner", 64 * 1024);     // R1
        models.put("deepseek-coder", 128 * 1024);       // Coder V2

        // OpenAI 系列
        models.put("gpt-4o", 128 * 1024);
        models.put("gpt-4-turbo", 128 * 1024);
        models.put("gpt-4", 32 * 1024);                 // gpt-4-32k
        models.put("gpt-3.5-turbo", 16 * 1024);

        // Anthropic 系列
        models.put("claude-3-5-sonnet", 200 * 1024);
        models.put("claude-3-opus", 200 * 1024);
        models.put("claude-3-haiku", 200 * 1024);

        // Ollama / 本地模型（常见模型族前缀）
        models.put("qwen", 128 * 1024);                 // Qwen 2.5+
        models.put("qwen2", 32 * 1024);                 // Qwen 2 (旧版)
        models.put("llama3", 128 * 1024);
        models.put("llama2", 32 * 1024);
        models.put("mistral", 32 * 1024);
        models.put("mixtral", 32 * 1024);
        models.put("gemma", 8 * 1024);
        models.put("phi", 128 * 1024);
        models.put("yi", 200 * 1024);

        MODEL_WINDOWS = Collections.unmodifiableMap(models);

        // ── 初始化场景预算 ──────────────────────────────────────
        Map<Scenario, Integer> budgets = new LinkedHashMap<>();
        budgets.put(Scenario.CHAT, DEFAULT_CHAT_BUDGET);
        // 小说需要追踪跨章节的情节脉络、角色关系和世界设定，
        // 较大的上下文窗口可以减少压缩频率，保证故事连贯性。
        budgets.put(Scenario.NOVEL, 200 * 1024);
        budgets.put(Scenario.CODE, 128 * 1024);
        budgets.put(Scenario.ANALYSIS, 128 * 1024);

        SCENARIO_BUDGETS = Collections.unmodifiableMap(budgets);
    }

    /** 当前运行时的模型名称（注入自配置） */
    private final String currentModel;

    /** 模型窗口查询缓存，避免每次遍历 MAP */
    private final ConcurrentHashMap<String, Integer> windowCache = new ConcurrentHashMap<>();

    /** 当前场景（可通过 setter 动态切换） */
    @Getter
    private Scenario currentScenario = Scenario.CHAT;

    public MaxTokenMenu(
            @Value("${spring.ai.deepseek.chat.options.model:deepseek-v4-flash}") String currentModel,
            @Value("${app.context.max-summary-tokens:80000}") int configuredThreshold) {
        this.currentModel = currentModel;
        log.info("MaxTokenMenu 初始化: 模型={}, 阈值={}", currentModel, configuredThreshold);
    }

    // ═════════════════════════════════════════════════════════════
    // 公开 API
    // ═════════════════════════════════════════════════════════════

    /**
     * 获取当前模型在当前场景下的有效最大上下文。
     *
     * @return min(模型最大窗口, 场景预算)
     */
    public int resolve() {
        return resolve(currentModel, currentScenario);
    }

    /**
     * 获取指定模型在指定场景下的有效最大上下文。
     *
     * @return min(模型最大窗口, 场景预算)
     */
    public int resolve(String modelName, Scenario scenario) {
        int window = lookupModelWindow(modelName);
        int budget = lookupScenarioBudget(scenario);
        int result = Math.min(window, budget);
        log.debug("maxToken resolve: model={} (window={}) scenario={} (budget={}) → {}",
                modelName, window, scenario, budget, result);
        return result;
    }

    /**
     * 获取当前模型的原始最大上下文窗口（不受场景影响）。
     */
    public int getModelWindow() {
        return lookupModelWindow(currentModel);
    }

    /**
     * 获取当前场景的 Token 预算。
     */
    public int getScenarioBudget() {
        return lookupScenarioBudget(currentScenario);
    }

    /**
     * 切换到指定场景，后续 resolve() 返回新场景下的预算。
     *
     * @param scenario 目标场景
     */
    public void switchTo(Scenario scenario) {
        Scenario old = this.currentScenario;
        this.currentScenario = scenario;
        int oldResolved = windowCache.size() > 0 ? resolve(currentModel, old) : DEFAULT_WINDOW;
        int newResolved = resolve(currentModel, scenario);
        log.info("场景切换: {} → {} (有效上下文 {} → {})",
                old.displayName(), scenario.displayName(),
                formatToken(oldResolved), formatToken(newResolved));
    }

    // ═════════════════════════════════════════════════════════════
    // 内部查找
    // ═════════════════════════════════════════════════════════════

    /**
     * 根据模型名称查找上下文窗口。
     * 规则：精确匹配 → 前缀匹配 → 默认 128K
     */
    private int lookupModelWindow(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return DEFAULT_WINDOW;
        }

        // 查缓存
        Integer cached = windowCache.get(modelName);
        if (cached != null) return cached;

        // 1. 精确匹配
        Integer exact = MODEL_WINDOWS.get(modelName);
        if (exact != null) {
            windowCache.put(modelName, exact);
            return exact;
        }

        // 2. 前缀匹配（按插入顺序，第一个匹配的生效）
        //    如 "qwen3.5:0.8b" → 匹配 key="qwen"
        for (Map.Entry<String, Integer> entry : MODEL_WINDOWS.entrySet()) {
            if (modelName.startsWith(entry.getKey())) {
                windowCache.put(modelName, entry.getValue());
                return entry.getValue();
            }
        }

        // 3. 未知模型 → 默认
        log.debug("未知模型 '{}'，使用默认窗口 {}K", modelName, DEFAULT_WINDOW / 1024);
        windowCache.put(modelName, DEFAULT_WINDOW);
        return DEFAULT_WINDOW;
    }

    /**
     * 根据场景查找 Token 预算。
     */
    private int lookupScenarioBudget(Scenario scenario) {
        return SCENARIO_BUDGETS.getOrDefault(scenario, DEFAULT_WINDOW);
    }

    // ═════════════════════════════════════════════════════════════
    // 启动日志
    // ═════════════════════════════════════════════════════════════

    @PostConstruct
    public void logStartup() {
        int resolved = resolve();
        log.info("──────────────────────────────────────────");
        log.info("  MaxTokenMenu 就绪");
        log.info("  当前模型:       {} ({})", currentModel, formatToken(getModelWindow()));
        log.info("  当前场景:       {} ({})", currentScenario, formatToken(getScenarioBudget()));
        log.info("  有效上下文:     {}", formatToken(resolved));
        log.info("  压缩硬阈值:     {} ({}%)", formatToken((int) (resolved * SummarizingChatMemoryRepository.HARD_RATIO)),
                (int) (SummarizingChatMemoryRepository.HARD_RATIO * 100));
        log.info("──────────────────────────────────────────");
    }

    private static String formatToken(int tokens) {
        if (tokens >= 1024 * 1024) return String.format("%.1fM (%d)", tokens / (1024.0 * 1024.0), tokens);
        return String.format("%dK (%d)", tokens / 1024, tokens);
    }

    // ═════════════════════════════════════════════════════════════
    // 场景枚举
    // ═════════════════════════════════════════════════════════════

    /**
     * 对话场景。不同场景有不同 Token 预算，
     * high-level 预算是 min(模型窗口, 场景预算)。
     */
    public enum Scenario {
        /** 日常对话、问答 —— 不需要超大上下文 */
        CHAT("日常对话"),
        /** 小说生成 —— 需要跨章节维持情节连贯性 */
        NOVEL("小说生成"),
        /** 编码任务 —— 需要阅读项目文件 */
        CODE("编码任务"),
        /** 文档分析 —— 需要摄入长文档 */
        ANALYSIS("文档分析");

        private final String displayName;

        Scenario(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }
}
