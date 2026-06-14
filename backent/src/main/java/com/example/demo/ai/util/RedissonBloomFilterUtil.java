package com.example.demo.ai.util;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

/**
 * Redis 布隆过滤器工具类
 *
 * 布隆过滤器用于高效判断某个元素是否"可能存在"或"一定不存在"。
 * 适用于知识库去重、缓存穿透防护等场景。
 *
 * 特点：
 * - 空间效率极高（比 HashSet 节省大量内存）
 * - 有一定误判率（可通过 falseProbability 控制）
 * - 不可删除元素
 *
 * @param expectedInsertions 预期插入元素数量
 * @param falseProbability   可接受的误判率（如 0.01 = 1%）
 */
public class RedissonBloomFilterUtil {

    private final RBloomFilter<String> bloomFilter;

    public RedissonBloomFilterUtil(RedissonClient redissonClient, String filterName) {
        this.bloomFilter = redissonClient.getBloomFilter(filterName);
    }

    public void init(long expectedInsertions, double falseProbability) {
        bloomFilter.tryInit(expectedInsertions, falseProbability);
    }

    public boolean contains(String value) {
        return bloomFilter.contains(value);
    }

    public void add(String value) {
        bloomFilter.add(value);
    }
}
