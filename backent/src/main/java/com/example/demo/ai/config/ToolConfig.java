package com.example.demo.ai.config;

import com.example.demo.ai.tools.GetTimeTool;
import com.example.demo.ai.tools.GetUserLocationTool;
import com.example.demo.ai.tools.GetWeatherTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * AI Tool 注册配置
 *
 * 显式地注册所有自定义 Tool Bean，避免 Spring 自动扫描到系统内部类。
 * 注册后的 Tool 会通过 MethodToolCallbackProvider 转换为 AI 模型可调用的函数。
 *
 * 当前已注册的 Tool：
 * - GetTimeTool         获取当前日期时间
 * - GetUserLocationTool 获取用户地理位置（交互式）
 * - GetWeatherTool      获取天气信息（模拟）
 */
@Configuration
public class ToolConfig {

    @Bean("myCustomTools")
    public List<Object> myCustomTools(
            GetTimeTool timeTool,
            GetUserLocationTool locationTool,
            GetWeatherTool weatherTool) {
        return List.of(timeTool, locationTool, weatherTool);
    }
}
