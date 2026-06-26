package com.example.demo.ai.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 测试 SummarizingChatMemoryRepository 的异步压缩机制。
 *
 * 覆盖场景：
 * - Token 估算触发
 * - 异步不阻塞（无死锁）
 * - 压缩冷却期
 * - 并发安全
 * - 边界情况
 */
class SummarizingChatMemoryRepositoryTest {

    private ChatMemoryRepository delegate;
    private ChatModel chatModel;
    private SummarizingChatMemoryRepository repo;

    /** 阈值设低：900 chars ≈ 500 tokens（含安全余量），方便短消息触发 */
    private static final int MAX_TOKENS = 900;
    private static final int COOLDOWN_ROUNDS = 0;

    /** 用于生成不同消息内容的计数器，防止 LinkedHashSet 去重 */
    private static int msgCounter = 0;

    /** 生成长消息（确保超过阈值） */
    private static String longMsg(String prefix) {
        return prefix + " [" + (msgCounter++) + "] " + "x".repeat(500);
    }

    @BeforeEach
    void setUp() {
        delegate = new InMemoryChatMemoryRepository();
        chatModel = Mockito.mock(ChatModel.class);
        repo = new SummarizingChatMemoryRepository(delegate, MAX_TOKENS, chatModel, COOLDOWN_ROUNDS);
        msgCounter = 0;
    }

    // ========================================================================
    // 1. 不超过阈值时不触发压缩
    // ========================================================================

    @Test
    @Timeout(10)
    void shortConversationShouldNotTriggerCompression() throws Exception {
        String convId = "test-short";

        // 5 轮短对话，总 ~500 chars ≈ ~275 tokens，远低于 900
        // 每次发不同的消息，防止 LinkedHashSet 去重
        for (int i = 1; i <= 5; i++) {
            repo.saveAll(convId, List.of(
                    new UserMessage("你好，今天是星期" + i + "？"),
                    new AssistantMessage("星期" + i + "，天气很好。")
            ));
        }

        Thread.sleep(1000);

        List<Message> history = delegate.findByConversationId(convId);
        assertTrue(history.size() >= 8,
                "短对话不应触发压缩，实际大小: " + history.size());
    }

    // ========================================================================
    // 2. 超过阈值时触发异步压缩
    // ========================================================================

    @Test
    @Timeout(15)
    void longConversationShouldTriggerAsyncCompression() throws Exception {
        String convId = "test-long";

        // 准备一个延迟完成的 latch，确保压缩任务真的跑了
        CountDownLatch compressLatch = new CountDownLatch(1);
        when(chatModel.call(anyString())).thenAnswer(invocation -> {
            String result = "【对话摘要】测试摘要内容，包含用户问题和助手回复。";
            compressLatch.countDown();
            return result;
        });

        // 发送 4 轮长消息（每条 ~600 chars，4 轮 = ~4800 chars ≈ ~2640 tokens >> 900）
        for (int i = 1; i <= 4; i++) {
            repo.saveAll(convId, List.of(
                    new UserMessage(longMsg("用户第" + i + "轮")),
                    new AssistantMessage(longMsg("助手第" + i + "轮"))
            ));
        }

        // 等待异步压缩完成
        boolean compressed = compressLatch.await(5, TimeUnit.SECONDS);
        assertTrue(compressed, "异步压缩应该被触发并完成");

        // 再等一下让压缩写入完成
        Thread.sleep(500);

        List<Message> history = delegate.findByConversationId(convId);
        boolean hasSummary = history.stream()
                .anyMatch(m -> m.getText().contains("【对话摘要】"));
        assertTrue(hasSummary, "压缩后消息应包含摘要内容");

        System.out.println("压缩后历史消息数: " + history.size()
                + " (原始 ~8 条), 摘要内容: "
                + (hasSummary ? "包含" : "不包含"));
    }

    // ========================================================================
    // 3. saveAll 不阻塞（异步核心，验证无死锁）
    // ========================================================================

    @Test
    @Timeout(5)
    void saveAllShouldNotBlockOnSlowModelCall() throws Exception {
        String convId = "test-nonblocking";

        // Mock 延迟 3 秒 —— 模拟慢 API
        CountDownLatch modelCalled = new CountDownLatch(1);
        when(chatModel.call(anyString())).thenAnswer(invocation -> {
            modelCalled.countDown();
            Thread.sleep(3000);
            return "【对话摘要】慢速摘要。";
        });

        long start = System.currentTimeMillis();

        // 触发压缩
        for (int i = 1; i <= 8; i++) {
            repo.saveAll(convId, List.of(
                    new UserMessage(longMsg("msg" + i)),
                    new AssistantMessage(longMsg("reply" + i))
            ));
        }

        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 2000,
                "saveAll 不应被模型调用阻塞，实际耗时: " + elapsed + "ms");

        // 确认模型调用确实被触发了（说明压缩逻辑正常进入了）
        boolean called = modelCalled.await(5, TimeUnit.SECONDS);
        assertTrue(called, "模型应该在后台被调用");

        List<Message> history = delegate.findByConversationId(convId);
        assertFalse(history.isEmpty(), "异步压缩完成前应保留历史");
    }

    // ========================================================================
    // 4. 冷却期机制
    // ========================================================================

    @Test
    @Timeout(20)
    void compressionShouldRespectCooldown() throws Exception {
        SummarizingChatMemoryRepository coolRepo = new SummarizingChatMemoryRepository(
                delegate, 1000, chatModel, 2);
        String convId = "test-cooldown";

        AtomicInteger callCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        when(chatModel.call(anyString())).thenAnswer(invocation -> {
            callCount.incrementAndGet();
            latch.countDown();
            Thread.sleep(100);
            return "【对话摘要】第" + callCount.get() + "次压缩。";
        });

        // 3 轮 × 2 条/轮 × ~550 chars/条 = ~3300 chars ≈ 1815 tokens >> 1000
        // cooldown=2：
        // 第1轮: counter 0→1 (不触发, cooldown=2, 0<2)
        // 第2轮: counter 1→2 (不触发, 1<2)
        // 第3轮: counter 2→0 (触发! 2>=2)
        for (int i = 1; i <= 3; i++) {
            coolRepo.saveAll(convId, List.of(
                    new UserMessage(longMsg("第一轮消息" + i)),
                    new AssistantMessage(longMsg("第一轮回复" + i))
            ));
        }
        latch.await(5, TimeUnit.SECONDS);
        Thread.sleep(1000);

        assertEquals(1, callCount.get(), "cooldown=2 时，前 3 轮应只触发 1 次压缩");
    }

    // ========================================================================
    // 5. 并发写入不产生死锁
    // ========================================================================

    @Test
    @Timeout(15)
    void concurrentWritesShouldNotDeadlock() throws Exception {
        String convId = "test-concurrent";
        int threadCount = 5;
        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            int threadId = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < 5; i++) {
                        repo.saveAll(convId, List.of(
                                new UserMessage("并发线程" + threadId + "-" + i),
                                new AssistantMessage("线程回复" + threadId + "-" + i)
                        ));
                        Thread.sleep(10);
                    }
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("线程 " + threadId + " 异常: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        boolean allFinished = latch.await(10, TimeUnit.SECONDS);
        assertTrue(allFinished, "所有线程应在超时前完成");
        assertEquals(threadCount, successCount.get(), "所有线程都应成功");

        List<Message> history = delegate.findByConversationId(convId);
        assertFalse(history.isEmpty(), "并发写入后历史不应为空");
    }

    // ========================================================================
    // 6. 并发读写
    // ========================================================================

    @Test
    @Timeout(15)
    void concurrentReadWriteShouldNotDeadlock() throws Exception {
        String convId = "test-rw-concurrent";
        int threadCount = 4;
        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch allDone = new CountDownLatch(threadCount);

        repo.saveAll(convId, List.of(
                new UserMessage("初始消息"),
                new AssistantMessage("初始回复")
        ));

        for (int t = 0; t < threadCount; t++) {
            int threadId = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < 10; i++) {
                        if (threadId % 2 == 0) {
                            repo.saveAll(convId, List.of(
                                    new UserMessage("写" + threadId + "-" + i),
                                    new AssistantMessage("写回" + threadId + "-" + i)
                            ));
                        } else {
                            delegate.findByConversationId(convId);
                        }
                        Thread.sleep(5);
                    }
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("线程 " + threadId + " 异常: " + e.getMessage());
                } finally {
                    allDone.countDown();
                }
            }).start();
        }

        boolean finished = allDone.await(10, TimeUnit.SECONDS);
        assertTrue(finished, "所有读写线程应在超时前完成");
        assertEquals(threadCount, successCount.get());
    }

    // ========================================================================
    // 7. 边界情况
    // ========================================================================

    @Test
    void emptyMessageListShouldNotThrow() {
        assertDoesNotThrow(() -> repo.saveAll("test-empty", List.of()));
    }

    @Test
    void nullMessageListShouldNotThrow() {
        assertDoesNotThrow(() -> repo.saveAll("test-null", null));
    }

    @Test
    @Timeout(5)
    void differentConversationsShouldNotInterfere() throws Exception {
        for (int i = 1; i <= 4; i++) {
            repo.saveAll("conv-a", List.of(
                    new UserMessage("A-msg-" + i),
                    new AssistantMessage("A-reply-" + i)
            ));
            repo.saveAll("conv-b", List.of(
                    new UserMessage("B-msg-" + i),
                    new AssistantMessage("B-reply-" + i)
            ));
        }
        Thread.sleep(500);
        assertEquals(delegate.findByConversationId("conv-a").size(),
                     delegate.findByConversationId("conv-b").size());
    }

    @Test
    void deleteShouldRemoveHistory() {
        String convId = "test-delete";
        repo.saveAll(convId, List.of(
                new UserMessage("测试"),
                new AssistantMessage("测试回复")
        ));
        repo.deleteByConversationId(convId);
        assertTrue(delegate.findByConversationId(convId).isEmpty());
    }

    // ========================================================================
    // 8. 多次压缩（验证压缩系统能反复工作）
    // ========================================================================

    @Test
    @Timeout(30)
    void multipleCompressionsShouldWork() throws Exception {
        String convId = "test-multi";
        AtomicInteger callCount = new AtomicInteger(0);

        when(chatModel.call(anyString())).thenAnswer(invocation -> {
            callCount.incrementAndGet();
            return "【对话摘要】第" + callCount.get() + "次摘要。";
        });

        // 第一轮：大消息触发压缩
        for (int i = 1; i <= 4; i++) {
            repo.saveAll(convId, List.of(
                    new UserMessage(longMsg("第一轮" + i)),
                    new AssistantMessage(longMsg("第一轮回复" + i))
            ));
        }
        Thread.sleep(1000);
        int firstCount = callCount.get();
        assertTrue(firstCount >= 1, "第一轮应触发至少 1 次压缩 (实际: " + firstCount + ")");

        // 第二轮：再发大消息，应再次触发
        for (int i = 1; i <= 4; i++) {
            repo.saveAll(convId, List.of(
                    new UserMessage(longMsg("第二轮" + i)),
                    new AssistantMessage(longMsg("第二轮回复" + i))
            ));
        }
        Thread.sleep(2000);
        assertTrue(callCount.get() > firstCount,
                "第二轮应再次触发压缩 (" + firstCount + " → " + callCount.get() + ")");
    }
}
