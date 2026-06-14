package com.example.demo.ai.util;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class RedisUtil {
    private final RedisTemplate<String, Object> redisTemplate;

    // 写入缓存
    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    // 写入缓存并设置过期时间
    public void set(String key, Object value, long time) {
        redisTemplate.opsForValue().set(key, value, time, TimeUnit.SECONDS);
    }

    // 读取缓存
    public Object get(String key) {
        return key == null ? null : redisTemplate.opsForValue().get(key);
    }

    // 删除缓存
    public void del(String key) {
        redisTemplate.delete(key);
    }

    // 判断 Key 是否存在
    public boolean hasKey(String key) {
        return redisTemplate.hasKey(key);
    }

    public Object rightPop(String key, long timeout, TimeUnit unit) {
        return redisTemplate.opsForList().rightPop(key, timeout, unit);
    }

    public void leftPush(String key, Object value) {
        redisTemplate.opsForList().leftPush(key, value);
    }
    /**
     * 向列表左侧推入元素并设置过期时间（Pipeline 方式）
     */
    public void leftPush(String key, Object value, long timeout, TimeUnit unit) {
        String script = "redis.call('lpush', KEYS[1], ARGV[1])\n" +
                "redis.call('expire', KEYS[1], ARGV[2])\n" +
                "return 1";

        Long seconds = unit.toSeconds(timeout);
        redisTemplate.execute(
                new DefaultRedisScript<>(script, Long.class),
                Collections.singletonList(key),
                value,
                seconds.toString()
        );
    }

    public boolean expire(String key, long time, TimeUnit unit) {
        return redisTemplate.expire(key, time, unit);
            }

}
