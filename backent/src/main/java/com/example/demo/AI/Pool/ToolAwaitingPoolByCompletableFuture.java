package com.example.demo.AI.Pool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@Slf4j
public class ToolAwaitingPoolByCompletableFuture {

    private static final long TIMEOUT_SECONDS = 30;
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingFutures = new ConcurrentHashMap<>();

    public String waitForResult(String chatId) throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();
        CompletableFuture<String> existing = pendingFutures.putIfAbsent(chatId, future);
        if (existing != null) {
            future = existing;
        }

        try {
            log.info("Tool execution paused, waiting for user response, chatId={}", chatId);
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

    public void completeResult(String chatId, String data) {
        CompletableFuture<String> future = pendingFutures.remove(chatId);
        if (future != null) {
            log.info("User response received, resuming tool execution, chatId={}, data={}", chatId, data);
            future.complete(data);
        } else {
            log.warn("No pending tool call found for chatId={}", chatId);
        }
    }

    public boolean hasPending(String chatId) {
        return pendingFutures.containsKey(chatId);
    }
}
