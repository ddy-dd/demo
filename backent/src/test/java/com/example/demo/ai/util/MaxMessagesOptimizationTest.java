package com.example.demo.ai.util;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.junit.jupiter.api.Test;

import java.util.*;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
public class MaxMessagesOptimizationTest {

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private ChatModel chatModel;

    // 30轮复杂对话场景，包含多次话题切换和信息回溯
    private static final String[] CONVERSATION_SCENARIO = {
            // 第1-5轮：建立基本信息
            "你好，我叫张伟，是一名Java开发工程师",
            "我最喜欢的编程语言是Java和Python",
            "我目前在做一个AI聊天机器人项目",
            "这个项目使用了Spring AI框架",
            "我的技术栈主要是Spring Boot + Redis + Milvus",

            // 第6-10轮：话题切换 - 技术问题
            "能帮我解释一下ChatMemory是怎么工作的吗？",
            "那MessageWindowChatMemory和SummarizingChatMemoryRepository有什么区别？",
            "我在考虑应该设置多少条消息作为上下文窗口",
            "如果消息太多会不会影响性能？",
            "Token消耗会很大吗？",

            // 第11-15轮：话题切换 - 个人生活
            "换个话题，我周末喜欢打篮球",
            "我还喜欢看科幻电影，特别是《三体》",
            "对了，我有个宠物猫叫咪咪",
            "我住在北京海淀区",
            "我的生日是5月20日",

            // 第16-20轮：回溯旧信息 - 测试记忆
            "你还记得我叫什么名字吗？",
            "我的职业是什么来着？",
            "我正在做的项目用了哪些技术？",
            "我最喜欢的编程语言是什么？",
            "我的宠物叫什么名字？",

            // 第21-25轮：复杂上下文关联
            "基于我之前说的技术栈，你觉得我应该用哪种向量数据库？",
            "考虑到我是Java开发，有什么好的AI框架推荐吗？",
            "结合我的项目需求，maxMessages设置为多少比较合适？",
            "我的生日快到了，能给我推荐一些适合程序员的礼物吗？",
            "作为一个住在北京的开发者，周末有什么好的技术活动推荐吗？",

            // 第26-30轮：综合测试
            "总结一下你对我的了解",
            "我之前提到的电影是什么？",
            "我住在哪里？我的生日是哪天？",
            "我的项目遇到了内存问题，基于你对我项目的了解，有什么建议吗？",
            "最后问一下，你能列出我之前告诉你的所有关键信息吗？"
    };

    // 预期答案关键词，用于评估价值度
    private static final Map<Integer, List<String>> EXPECTED_KEYWORDS = new HashMap<>() {{
        put(15, Arrays.asList("张伟"));
        put(16, Arrays.asList("Java", "开发", "工程师"));
        put(17, Arrays.asList("Spring", "Redis", "Milvus", "AI"));
        put(18, Arrays.asList("Java", "Python"));
        put(19, Arrays.asList("咪咪", "猫"));
        put(20, Arrays.asList("Milvus", "向量数据库"));
        put(21, Arrays.asList("Spring AI", "Java"));
        put(25, Arrays.asList("张伟", "Java", "北京", "5月20"));
        put(26, Arrays.asList("三体", "科幻"));
        put(27, Arrays.asList("北京", "海淀", "5月20"));
        put(29, Arrays.asList("张伟", "Java", "Spring", "咪咪", "北京"));
    }};

    @Test
    public void testMaxMessagesOptimization() {
        System.out.println("=".repeat(100));
        System.out.println("开始测试 maxMessages 最佳值");
        System.out.println("=".repeat(100));

        int[] maxMessagesValues = {5, 10, 20, 30};
        Map<Integer, TestResult> results = new HashMap<>();

        for (int maxMessages : maxMessagesValues) {
            System.out.println("\n" + "=".repeat(100));
            System.out.println("测试 maxMessages = " + maxMessages);
            System.out.println("=".repeat(100));

            TestResult result = runConversationTest(maxMessages);
            results.put(maxMessages, result);

            printDetailedResult(maxMessages, result);
        }

        System.out.println("\n" + "=".repeat(100));
        System.out.println("最终评估报告");
        System.out.println("=".repeat(100));
        printFinalReport(results);
    }

    private TestResult runConversationTest(int maxMessages) {
        String conversationId = "test-" + maxMessages + "-" + System.currentTimeMillis();
        TestResult result = new TestResult(maxMessages);

        InMemoryChatMemoryRepository baseRepo = new InMemoryChatMemoryRepository();
        SummarizingChatMemoryRepository summarizingRepo = new SummarizingChatMemoryRepository(
                baseRepo, 50000, chatModel, 2
        );
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(summarizingRepo)
                .maxMessages(maxMessages)
                .build();

        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();

        for (int i = 0; i < CONVERSATION_SCENARIO.length; i++) {
            String userInput = CONVERSATION_SCENARIO[i];
            System.out.println("\n[第" + (i + 1) + "轮] 用户: " + userInput);

            long startTime = System.currentTimeMillis();
            long startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            String response = chatClientBuilder.defaultAdvisors(memoryAdvisor).build()
                    .prompt()
                    .options(OllamaChatOptions.builder().disableThinking().build())
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .user(userInput)
                    .call()
                    .content();

            long endTime = System.currentTimeMillis();
            long endMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

            long responseTime = endTime - startTime;
            long memoryUsed = endMemory - startMemory;

            System.out.println("AI: " + response);
            System.out.println("⏱️  响应时间: " + responseTime + "ms");

            result.addResponseTime(responseTime);
            result.addMemoryUsage(memoryUsed);

            // 评估价值度：检查是否包含预期关键词
            if (EXPECTED_KEYWORDS.containsKey(i)) {
                boolean foundAllKeywords = true;
                List<String> keywords = EXPECTED_KEYWORDS.get(i);
                for (String keyword : keywords) {
                    if (!response.contains(keyword)) {
                        foundAllKeywords = false;
                        result.addValueIssue("第" + (i + 1) + "轮未能准确回忆: " + keyword);
                        System.out.println("❌ 价值度问题: 未包含关键词 '" + keyword + "'");
                    }
                }
                if (foundAllKeywords) {
                    System.out.println("✅ 价值度良好: 准确回忆了所有关键信息");
                }
            }

            // 评估感知度：检查回复长度和连贯性（简单启发式）
            if (response.length() < 10) {
                result.addPerceptionIssue("第" + (i + 1) + "轮回复过于简短");
                System.out.println("⚠️  感知度问题: 回复过于简短");
            }

            // 延迟避免请求过快
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return result;
    }

    private void printDetailedResult(int maxMessages, TestResult result) {
        System.out.println("\n" + "-".repeat(80));
        System.out.println("详细测试结果 (maxMessages = " + maxMessages + ")");
        System.out.println("-".repeat(80));

        double valueScore = result.calculateValueScore();
        double perceptionScore = result.calculatePerceptionScore();
        double performanceScore = result.calculatePerformanceScore();
        double totalScore = result.calculateTotalScore();

        System.out.println("\n📊 评分详情:");
        System.out.println("  价值度 (50%):     " + String.format("%.2f", valueScore) + " / 50.00");
        System.out.println("  感知度 (30%):     " + String.format("%.2f", perceptionScore) + " / 30.00");
        System.out.println("  性能成本 (20%):   " + String.format("%.2f", performanceScore) + " / 20.00");
        System.out.println("  " + "-".repeat(40));
        System.out.println("  总分:             " + String.format("%.2f", totalScore) + " / 100.00");

        System.out.println("\n📈 性能指标:");
        System.out.println("  平均响应时间:     " + String.format("%.0f", result.getAverageResponseTime()) + " ms");
        System.out.println("  最大响应时间:     " + result.getMaxResponseTime() + " ms");
        System.out.println("  平均内存使用:     " + String.format("%.2f", result.getAverageMemoryUsage() / 1024.0 / 1024.0) + " MB");

        if (!result.getValueIssues().isEmpty()) {
            System.out.println("\n❌ 价值度问题 (" + result.getValueIssues().size() + "个):");
            result.getValueIssues().forEach(issue -> System.out.println("  - " + issue));
        }

        if (!result.getPerceptionIssues().isEmpty()) {
            System.out.println("\n⚠️  感知度问题 (" + result.getPerceptionIssues().size() + "个):");
            result.getPerceptionIssues().forEach(issue -> System.out.println("  - " + issue));
        }
    }

    private void printFinalReport(Map<Integer, TestResult> results) {
        System.out.println("\n综合对比:");
        System.out.println("-".repeat(100));
        System.out.printf("%-15s | %-12s | %-12s | %-12s | %-12s | %-15s%n",
                "maxMessages", "价值度", "感知度", "性能成本", "总分", "平均响应时间");
        System.out.println("-".repeat(100));

        int bestMaxMessages = -1;
        double bestScore = -1;

        for (Map.Entry<Integer, TestResult> entry : results.entrySet()) {
            int maxMessages = entry.getKey();
            TestResult result = entry.getValue();
            double totalScore = result.calculateTotalScore();

            System.out.printf("%-15d | %-12.2f | %-12.2f | %-12.2f | %-12.2f | %-15.0f ms%n",
                    maxMessages,
                    result.calculateValueScore(),
                    result.calculatePerceptionScore(),
                    result.calculatePerformanceScore(),
                    totalScore,
                    result.getAverageResponseTime());

            if (totalScore > bestScore) {
                bestScore = totalScore;
                bestMaxMessages = maxMessages;
            }
        }

        System.out.println("-".repeat(100));
        System.out.println("\n🏆 最佳推荐:");
        System.out.println("  maxMessages = " + bestMaxMessages);
        System.out.println("  综合得分 = " + String.format("%.2f", bestScore) + " / 100.00");
        System.out.println("\n💡 建议:");

        TestResult bestResult = results.get(bestMaxMessages);
        if (bestMaxMessages <= 10) {
            System.out.println("  - 当前最佳值较小，适合短期对话，但可能在长对话中丢失重要信息");
            System.out.println("  - 如果需要更长的上下文记忆，可以考虑适当增加");
        } else if (bestMaxMessages >= 25) {
            System.out.println("  - 当前最佳值较大，提供了良好的上下文记忆");
            System.out.println("  - 注意监控Token消耗和响应时间，确保成本可控");
        } else {
            System.out.println("  - 当前最佳值在中等范围，较好地平衡了记忆能力和性能");
            System.out.println("  - 可以根据实际业务场景微调");
        }

        System.out.println("\n  平均响应时间: " + String.format("%.0f", bestResult.getAverageResponseTime()) + " ms");
        System.out.println("  价值度问题数: " + bestResult.getValueIssues().size());
        System.out.println("  感知度问题数: " + bestResult.getPerceptionIssues().size());
    }

    // 测试结果类
    static class TestResult {
        private final int maxMessages;
        private final List<Long> responseTimes = new ArrayList<>();
        private final List<Long> memoryUsages = new ArrayList<>();
        private final List<String> valueIssues = new ArrayList<>();
        private final List<String> perceptionIssues = new ArrayList<>();

        public TestResult(int maxMessages) {
            this.maxMessages = maxMessages;
        }

        public void addResponseTime(long time) {
            responseTimes.add(time);
        }

        public void addMemoryUsage(long memory) {
            memoryUsages.add(memory);
        }

        public void addValueIssue(String issue) {
            valueIssues.add(issue);
        }

        public void addPerceptionIssue(String issue) {
            perceptionIssues.add(issue);
        }

        public double calculateValueScore() {
            // 价值度：50分满分，每个问题扣10分
            double score = 50.0 - (valueIssues.size() * 10.0);
            return Math.max(0, score);
        }

        public double calculatePerceptionScore() {
            // 感知度：30分满分，每个问题扣5分
            double score = 30.0 - (perceptionIssues.size() * 5.0);
            return Math.max(0, score);
        }

        public double calculatePerformanceScore() {
            // 性能成本：20分满分
            double avgResponseTime = getAverageResponseTime();
            double score = 20.0;

            // 响应时间超过2秒，扣10分
            if (avgResponseTime > 2000) {
                score -= 10;
            }
            // 响应时间超过5秒，再扣10分
            if (avgResponseTime > 5000) {
                score -= 10;
            }
            // 最大响应时间超过10秒，扣5分
            if (getMaxResponseTime() > 10000) {
                score -= 5;
            }

            return Math.max(0, score);
        }

        public double calculateTotalScore() {
            return calculateValueScore() + calculatePerceptionScore() + calculatePerformanceScore();
        }

        public double getAverageResponseTime() {
            return responseTimes.stream().mapToLong(Long::longValue).average().orElse(0);
        }

        public long getMaxResponseTime() {
            return responseTimes.stream().mapToLong(Long::longValue).max().orElse(0);
        }

        public double getAverageMemoryUsage() {
            return memoryUsages.stream().mapToLong(Long::longValue).average().orElse(0);
        }

        public List<String> getValueIssues() {
            return valueIssues;
        }

        public List<String> getPerceptionIssues() {
            return perceptionIssues;
        }
    }
}
