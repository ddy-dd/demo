package com.example.demo.AI.Config;

import com.example.demo.AI.Tools.GetTimeTool;
import com.example.demo.AI.Tools.GetUserLocationTool;
import com.example.demo.AI.Tools.GetWeatherTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class ToolConfig {

    // 显式地在这里列出你所有的工具类
    // 这样 Spring 只会把这两个 Bean 放进列表，绝不会包含系统内部类
    @Bean("myCustomTools")
    public List<Object> myCustomTools(GetTimeTool timeTool, GetUserLocationTool locationTool, GetWeatherTool weatherTool) {
        return List.of(timeTool, locationTool, weatherTool);
    }
}
