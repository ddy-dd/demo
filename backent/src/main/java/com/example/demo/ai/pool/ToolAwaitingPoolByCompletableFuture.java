package com.example.demo.ai.pool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 交互式 Tool Calling 等待池（基于 CompletableFuture）
 *
 * <h3>为什么需要这个？</h3>
 * 传统的 Tool Calling 是纯函数的：AI 调用 Tool → Tool 执行并返回结果。
 * 但有些 Tool 需要用户交互（例如获取地理位置），AI 线程必须挂起等待用户响应。
 *
 * <h3>工作机制</h3>
 * <pre>
 * 1. AI 调用 GetUserLocationTool
 * 2. Tool 向后端 WebSocket 发送 "tools" 请求
 * 3. Tool 调用 waitForResult(chatId) → 当前线程被 CompletableFuture.get() 阻塞
 * 4. 前端收到请求 → 获取位置 → 通过 WebSocket 回传
 * 5. 后端调用 completeResult(chatId, data) → CompletableFuture 完成
 * 6. Tool 线程恢复执行，拿到前端回传数据
 * 7. 若 30 秒超时 → 抛出 TimeoutException
 * </pre>
 *
 * 对应基于 Redis 的分布式版本见 {@link com.example.demo.ai.pool.ToolAwaitingPoolByRedis}
 */
@Component
@Slf4j
public class ToolAwaitingPoolByCompletableFuture {

    /** 单次 Tool 调用的超时时间（秒） */
    private static final long TIMEOUT_SECONDS = 30;

    /** 等待池：chatId → CompletableFuture */
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingFutures = new ConcurrentHashMap<>();

    /**
     * 阻塞当前线程，等待用户通过 WebSocket 回传数据
     *
     * @param chatId 会话标识
     * @return 前端回传的 JSON 字符串
     * @throws TimeoutException 超时
     */
    public String waitForResult(String chatId) throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();
        CompletableFuture<String> existing = pendingFutures.putIfAbsent(chatId, future);
        if (existing != null) {
            future = existing;
        }

        try {
            log.info("Tool 执行暂停，等待用户响应, chatId={}", chatId);
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingFutures.remove(chatId);
            throw new TimeoutException("等待用户响应超时, chatId=" + chatId);
        } catch (Exception e) {
            pendingFutures.remove(chatId);
            throw e;
        } finally {
            pendingFutures.remove(chatId, future);
        }
    }

    /**
     * 用户响应到达，完成 CompletableFuture，唤醒 Tool 线程
     *
     * @param chatId 会话标识
     * @param data   前端回传的数据（JSON 字符串）
     */
    public void completeResult(String chatId, String data) {
        CompletableFuture<String> future = pendingFutures.remove(chatId);
        if (future != null) {
            log.info("收到用户响应，恢复 Tool 执行, chatId={}", chatId);
            future.complete(data);
        } else {
            log.warn("未找到等待中的 Tool 调用, chatId={}", chatId);
        }
    }

    /** 检查是否有正在等待的 Tool 调用 */
    public boolean hasPending(String chatId) {
        return pendingFutures.containsKey(chatId);
    }
}
