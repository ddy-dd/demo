package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        Map<String, String> map = new HashMap<>();
        map.put("1", "1");
        map.put("2", "2");
        map.put("3", "3");
        map.get("1");
        map.remove("1");
        map.size();

        Map<String, String> mao = new ConcurrentHashMap<>();
        SpringApplication.run(DemoApplication.class, args);
    }

}
