package com.example.demo.SimpleUtil;

import lombok.RequiredArgsConstructor;

import java.util.BitSet;

@RequiredArgsConstructor
public class SimpleBloomFilter {
    private final int size;
    private final int hashNum;
    private final BitSet seeds;

    public SimpleBloomFilter(int size, int hashNum) {
        this.size = size;
        this.hashNum = hashNum;
        this.seeds = new BitSet(size);
    }

    public void add(String e){
        for (int i = 0; i < hashNum; i++) {
            seeds.set(hash(e, i), true);
        }
    }

    public boolean contains(String e){
        for (int i = 0; i < hashNum; i++) {
            if (!seeds.get(hash(e, i))) {
                return false;
            }
        }
        return true;
    }

    private int baseHash(String e){
        int h;
        return (e == null) ? 0 : (h = e.hashCode()) ^ (h >>> 16);
    }

    private int hash(String e, int seed){
        return baseHash(e) + seed * 31;
    }

}
