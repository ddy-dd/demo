package com.example.demo.ai.pool;

import com.example.demo.ai.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 交互式 Tool Calling 等待池（基于 Redis 分布式版本）
 *
 * 与 {@link ToolAwaitingPoolByCompletableFuture} 功能相同，
 * 但使用 Redis List 的阻塞式读取实现跨进程通信。
 *
 * 适用于多实例部署场景：AI 实例 A 等待，WebSocket 实例 B 完成，
 * 通过 Redis 解耦。
 *
 * 工作机制：
 * <pre>
 * waitForResult:  BLPop key, timeout=30s
 * completeResult: LPush key, data
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolAwaitingPoolByRedis {

    private static final String TOOL_RESULT_PREFIX = "TOOL_AWAITING:";
    private static final long TIMEOUT_SECONDS = 30;

    private final RedisUtil redisUtil;

    /**
     * 阻塞等待用户通过 WebSocket 回传数据（基于 Redis 阻塞队列）
     *
     * @param chatId 会话标识
     * @return 前端回传的数据
     * @throws TimeoutException 超时
     */
    public Object waitForResult(String chatId) throws Exception {
        String key = TOOL_RESULT_PREFIX + chatId;
        Object result = redisUtil.rightPop(key, TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (result == null) {
            throw new TimeoutException("等待用户返回位置信息超时");
        }
        return result;
    }

    /**
     * 用户响应到达，通过 Redis 唤醒 Tool 线程
     *
     * @param chatId 会话标识
     * @param data   前端回传的数据
     */
    public void completeResult(String chatId, Object data) {
        String key = TOOL_RESULT_PREFIX + chatId;
        redisUtil.leftPush(key, data);
        redisUtil.expire(key, 1, TimeUnit.MINUTES);
    }
}
