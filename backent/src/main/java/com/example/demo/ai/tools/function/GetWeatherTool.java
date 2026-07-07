package com.example.demo.ai.tools.function;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 天气查询 Tool（模拟实现）
 *
 * 当前返回模拟数据，生产环境可替换为真实天气 API 调用。
 * 展示了如何为 AI 模型注册一个带参数的工具函数。
 */
@Component
public class GetWeatherTool {

    @Tool(description = "获取指定城市的天气信息")
    public String getWeather(String city) {
        // TODO: 替换为真实天气 API 调用（如和风天气 / OpenWeatherMap）
        return "【" + city + "】天气信息：晴转多云，温度 25°C，体感舒适";
    }
}
