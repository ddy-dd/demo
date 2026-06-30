package com.example.demo.ai.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 获取当前日期时间 Tool
 *
 * AI 可通过此 Tool 获取用户时区的当前日期和时间。
 * returnDirect = true 表示直接返回结果给用户，不经过 LLM 二次处理。
 */
@Component
public class GetTimeTool {

    @Tool(description = "获取用户当前时区的日期和时间")
    public String getCurrentDateTime() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(
                "yyyy-MM-dd HH:mm:ss EEEE", LocaleContextHolder.getLocale());

        return LocalDateTime.now()
                .atZone(LocaleContextHolder.getTimeZone().toZoneId())
                .format(formatter);
    }
}
