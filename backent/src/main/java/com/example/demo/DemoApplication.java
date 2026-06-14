package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI Demo 应用入口
 *
 * 基于 Spring Boot 3 + Spring AI 的智能对话应用，
 * 集成 DeepSeek 大模型、Milvus 向量库和 Ollama 本地模型。
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

}
