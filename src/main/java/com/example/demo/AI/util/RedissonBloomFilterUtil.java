package com.example.demo.AI.util;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

public class RedissonBloomFilterUtil {

    private final RBloomFilter<String> bloomFilter;

    public RedissonBloomFilterUtil(RedissonClient redissonClient, String fileName) {
        this.bloomFilter = redissonClient.getBloomFilter(fileName);
    }

    public void init(long expectedInsertions, double falseProbability){
        bloomFilter.tryInit(expectedInsertions, falseProbability);
    }

    public boolean contains(String value){
        return bloomFilter.contains(value);
    }

    public void add(String value){
        bloomFilter.add(value);
    }


}
