package com.example.demo.ai.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * ChatModel 配置
 *
 * 当存在多个 ChatModel Bean 时（DeepSeek + Ollama），
 * 将 DeepSeek 设为主要模型（@Primary）。
 */
@Configuration
public class ChatModelConfig {

    @Bean
    @Primary
    public ChatModel primaryChatModel(@Qualifier("deepSeekChatModel") ChatModel deepSeekChatModel) {
        return deepSeekChatModel;
    }
}
