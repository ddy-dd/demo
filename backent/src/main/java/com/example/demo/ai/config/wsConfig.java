package com.example.demo.ai.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

/**
 * WebSocket 配置
 *
 * 注册 ServerEndpointExporter，使 @ServerEndpoint 注解的类
 * 能够被 Spring 容器管理并暴露为 WebSocket 端点。
 * 使用 @ConditionalOnWebApplication 确保非 Web 环境下自动跳过。
 */
@Configuration
@ConditionalOnWebApplication
public class wsConfig {

    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }
}
