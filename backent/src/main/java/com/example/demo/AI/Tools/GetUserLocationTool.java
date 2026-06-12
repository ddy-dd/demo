package com.example.demo.AI.Tools;

import com.example.demo.AI.Pool.ToolAwaitingPoolByCompletableFuture;
import com.example.demo.AI.ServiceImpl.WebsocketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Slf4j
@Component()
@RequiredArgsConstructor
public class GetUserLocationTool {

    private final ToolAwaitingPoolByCompletableFuture toolAwaitingPool;

    @Tool(description = "当用户的提问需要依赖其当前地理位置时（例如问在哪里、附近有什么）")
    public String doRequestLocation(ToolContext toolContext) {
        try {
            String chatId = null;
            if (toolContext != null) {
                chatId = (String) toolContext.getContext().get("chatId");
            }
            WebsocketService.sendResponse(chatId, "tools", "location");
            Object locationData = toolAwaitingPool.waitForResult(chatId);
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writeValueAsString(locationData);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
