package com.example.demo.ai.tools.function;

import com.example.demo.ai.chat.service.ChatWebsocketService;
import com.example.demo.ai.tools.pool.ToolAwaitingPoolByCompletableFuture;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 用户地理位置获取 Tool
 *
 * <h3>交互式 Tool Calling 示例</h3>
 * 与其他 Tool 不同，这个 Tool 不是纯函数的 —— 它需要用户通过浏览器授权获取位置。
 * 整个交互流程：
 * <pre>
 * 1. AI 判断需要用户位置 → 调用 doRequestLocation()
 * 2. 通过 WebSocket 向前端发送 "tools/location" 请求
 * 3. 调用 toolAwaitingPool.waitForResult() → 当前线程阻塞等待
 * 4. 前端获取地理位置 → 通过 WebSocket 回传
 * 5. WebsocketService 收到 "tools" 消息 → 调用 completeResult() → Tool 恢复
 * 6. 返回位置数据给 AI
 * </pre>
 *
 * @see com.example.demo.ai.tools.pool.ToolAwaitingPoolByCompletableFuture 等待池实现
 * @see ChatWebsocketService WebSocket 通信
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetUserLocationTool {

    private final ToolAwaitingPoolByCompletableFuture toolAwaitingPool;

    @Tool(description = "当用户的提问需要依赖其当前地理位置时（例如问在哪里、附近有什么）")
    public String doRequestLocation(ToolContext toolContext) {
        try {
            // 从 ToolContext 中提取 chatId
            String chatId = null;
            if (toolContext != null) {
                chatId = (String) toolContext.getContext().get("chatId");
            }

            // 通过 WebSocket 请求前端获取地理位置
            ChatWebsocketService.sendResponse(chatId, "tools", "location", null);

            // 等待前端回传位置数据（当前线程会被阻塞）
            Object locationData = toolAwaitingPool.waitForResult(chatId);
            log.info("获取到用户位置: {}", locationData);

            // 将位置数据转为 JSON 字符串返回给 AI
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writeValueAsString(locationData);

        } catch (Exception e) {
            log.error("获取用户位置失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取用户位置失败: " + e.getMessage(), e);
        }
    }
}
