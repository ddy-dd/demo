package com.example.demo.AI.Tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class GetWeatherTool {
    @Tool(description = "获取指定城市的天气信息")
    public String getWeather(String city) {
        // 模拟调用天气API获取天气信息
        return "天气信息：晴转多云，温度 25°C";
    }
}
