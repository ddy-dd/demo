package com.example.demo.ai.tools.config;

import com.example.demo.ai.tools.function.GetTimeTool;
import com.example.demo.ai.tools.function.GetUserLocationTool;
import com.example.demo.ai.tools.function.GetWeatherTool;
import com.example.demo.ai.tools.function.FileWriterTool;
import com.example.demo.ai.tools.function.ImportDocumentTool;
import com.example.demo.ai.tools.function.NovelReaderTool;
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
 * - NovelReaderTool     读取上传的小说文件内容
 * - ImportDocumentTool  导入结构化 Markdown 到 novelbase 知识图谱
 * - FileWriterTool      将 .md 文件写入磁盘供导入
 * - novelbase MCP       小说创作知识图谱管理（由自动配置注入）
 */
@Configuration
public class ToolConfig {

    @Bean("myCustomTools")
    public List<Object> myCustomTools(
            GetTimeTool timeTool,
            GetUserLocationTool locationTool,
            GetWeatherTool weatherTool,
            NovelReaderTool novelReaderTool,
            ImportDocumentTool importDocumentTool,
            FileWriterTool fileWriterTool) {
        return List.of(timeTool, locationTool, weatherTool, novelReaderTool, importDocumentTool, fileWriterTool);
    }
}
