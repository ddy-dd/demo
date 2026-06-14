package com.example.demo.ai.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * API 认证与请求追踪过滤器
 *
 * 职责：
 * 1. 为每个请求生成 traceId，注入 MDC 用于日志链路追踪
 * 2. 如配置了 app.api-key，则校验 X-API-Key 请求头
 *
 * 设计说明：
 * - 当 app.api-key 为空字符串时跳过鉴权，方便本地开发调试
 * - 生产环境应设置 APP_API_KEY 环境变量启用鉴权
 * - WebSocket 连接不经过此过滤器，由 WebsocketService 自行校验
 */
@Slf4j
@Component
@Order(1)
public class ApiKeyAuthFilter implements Filter {

    /** 从配置读取 API Key，为空则跳过鉴权 */
    @Value("${app.api-key:}")
    private String apiKey;

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        // 1️⃣ 为当前请求生成 traceId，用于日志链路追踪
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("traceId", traceId);

        try {
            // 2️⃣ 如果配置了 API Key，校验请求头
            if (apiKey != null && !apiKey.isEmpty()) {
                String requestApiKey = request.getHeader("X-API-Key");
                if (!apiKey.equals(requestApiKey)) {
                    log.warn("[{}] API Key 校验失败: {}", traceId, request.getRequestURI());
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"status\":401,\"code\":\"UNAUTHORIZED\",\"message\":\"无效的 API Key\"}");
                    return;
                }
            }

            chain.doFilter(servletRequest, servletResponse);

        } finally {
            // 3️⃣ 清理 MDC，防止线程池复用导致上下文污染
            MDC.clear();
        }
    }
}
