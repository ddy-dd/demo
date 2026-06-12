package com.example.demo.AI.Tools;


import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component()
public class GetTimeTool {
    @Tool(description = "Get the current date and time in the user's timezone",returnDirect = true)
    public String getCurrentDateTime() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE", LocaleContextHolder.getLocale());

        return LocalDateTime.now()
                .atZone(LocaleContextHolder.getTimeZone().toZoneId())
                .format(formatter);
    }
}
