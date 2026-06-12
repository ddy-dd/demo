package com.example.demo.AI.Pool;

import java.util.concurrent.*;

import com.example.demo.AI.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ToolAwaitingPoolByRedis {

    private static final String TOOL_RESULT_PREFIX = "TOOL_AWAITING:";
    private static final long TIMEOUT_SECONDS = 30; // 最多等待30秒
    private final RedisUtil redisUtil;

    public Object waitForResult(String chatId) throws Exception {
        String key = TOOL_RESULT_PREFIX + chatId;

        // 利用 RedisUtil 调用封装好的阻塞式右出队操作
        Object result = redisUtil.rightPop(key, TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (result == null) {
            throw new TimeoutException("等待用户返回位置信息超时");
        }
        return result;
    }

    /**
     28     * WebSocket 收到前端数据时，将其放入 Redis 唤醒 Tool
     29     */
    public void completeResult(String chatId, Object data) {
        String key = TOOL_RESULT_PREFIX + chatId;

        // 利用 RedisUtil 进行左入队和设置过期时间
        redisUtil.leftPush(key, data);
        redisUtil.expire(key, 1, TimeUnit.MINUTES);
    }
}
