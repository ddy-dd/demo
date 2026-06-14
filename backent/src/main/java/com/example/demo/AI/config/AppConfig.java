package com.example.demo.ai.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 应用启动配置校验
 *
 * 在 Spring 容器初始化完成后校验关键配置是否正确，
 * 避免启动后因缺少环境变量导致运行时才崩溃。
 */
@Slf4j
@Component
public class AppConfig {

    @Value("${spring.ai.deepseek.api-key}")
    private String deepseekApiKey;

    @Value("${spring.ai.ollama.base-url}")
    private String ollamaBaseUrl;

    @Value("${spring.ai.vectorstore.milvus.client.host}")
    private String milvusHost;

    @Value("${spring.ai.vectorstore.milvus.client.port}")
    private int milvusPort;

    @Value("${app.api-key:}")
    private String appApiKey;

    @PostConstruct
    public void validateConfig() {
        log.info("========================================");
        log.info("  AI Demo 配置校验");
        log.info("========================================");
        log.info("  DeepSeek API Key:     {}", maskSecret(deepseekApiKey));
        log.info("  Ollama Base URL:      {}", ollamaBaseUrl);
        log.info("  Milvus:               {}:{}", milvusHost, milvusPort);
        log.info("  API Auth:             {}", appApiKey.isEmpty() ? "未启用（开发模式）" : "已启用");
        log.info("========================================");

        if (deepseekApiKey == null || deepseekApiKey.isEmpty() || "sk-your-key".equals(deepseekApiKey)) {
            log.warn("⚠ DeepSeek API Key 未配置！请设置环境变量 DEEPSEEK_API_KEY");
        }
    }

    /** 敏感信息脱敏，只显示前 8 位 */
    private String maskSecret(String secret) {
        if (secret == null || secret.length() < 12) return "(未配置)";
        return secret.substring(0, 8) + "****";
    }
}
