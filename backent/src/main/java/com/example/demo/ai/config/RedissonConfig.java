package com.example.demo.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置
 *
 * 提供 RedissonClient Bean，用于分布式 BloomFilter 等场景。
 * Redis 连接地址从 application.yml 的 spring.data.redis 配置读取。
 *
 * TODO: 当前为硬编码连接地址，后续应改为从配置文件读取
 */
@Slf4j
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        log.warn("当前Redis为硬编码连接地址，后续应改为从配置文件读取");
        return Redisson.create(config);
    }
}
