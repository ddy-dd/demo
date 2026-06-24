package com.example.demo.ai.serviceImpl;

import com.example.demo.ai.object.Communication;
import com.example.demo.ai.pool.ToolAwaitingPoolByCompletableFuture;
import com.example.demo.ai.serviceImpl.service.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 服务端 —— AI 对话的实时通信中枢
 *
 * <h3>通信协议</h3>
 * 所有消息使用 JSON 格式，参考 {@link Communication}：
 * <pre>
 * client → server:
 *   {"type":"text",  "data":"用户消息",    "uuid":"会话ID"}
 *   {"type":"over",  "data":"",            "uuid":"会话ID"}
 *   {"type":"ping"                         }
 *   {"type":"tools", "data":{位置数据}     }
 *
 * server → client:
 *   {"type":"thinking","data":"思考过程",   "uuid":"会话ID"}
 *   {"type":"text",    "data":"回复片段",   "uuid":"会话ID"}
 *   {"type":"over",    "data":null,        "uuid":"会话ID"}
 *   {"type":"error",   "data":"错误信息",   "uuid":"会话ID"}
 *   {"type":"tools",   "data":"location"   }
 *   {"type":"pong"                         }
 * </pre>
 *
 * <h3>流式控制</h3>
 * 使用 Reactor Flux + Disposable 管理 AI 流式响应。前端发送 "over" 时
 * 调用 disposable.dispose() 触发 doOnCancel 回调链，实现打断。
 *
 * <h3>交互式 Tool Calling</h3>
 * 当 AI 调用 GetUserLocationTool 等需要用户输入的 Tool 时，
 * 通过 WS 向前端请求数据，并通过 ToolAwaitingPoolByCompletableFuture
 * 挂起 AI 线程等待回传。详见 {@link com.example.demo.ai.pool.ToolAwaitingPoolByCompletableFuture}
 */
@ServerEndpoint(value = "/websocket/{chatId}")
@Slf4j
@Service
public class WebsocketService {

    private static ChatService chatService;
    private static ApplicationContext applicationContext;
    private static ToolAwaitingPoolByCompletableFuture toolAwaitingPool;

    /** 会话映射：chatId → WebSocket Session */
    private static final ConcurrentHashMap<String, Session> SESSION_MAP = new ConcurrentHashMap<>();

    /** 活跃流映射：conversationUUID → Disposable（用于打断） */
    private static final ConcurrentHashMap<String, Disposable> ACTIVE_STREAMS = new ConcurrentHashMap<>();

    /** 当前对话映射：chatId → conversationUUID（用于去重） */
    private static final ConcurrentHashMap<String, String> ACTIVE_CONVERSATION_MAP = new ConcurrentHashMap<>();

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        chatService = applicationContext.getBean(ChatService.class);
        toolAwaitingPool = applicationContext.getBean(ToolAwaitingPoolByCompletableFuture.class);
    }

    @Autowired
    public void setApplicationContext(ApplicationContext ctx) {
        applicationContext = ctx;
    }

    @OnOpen
    public void onOpen(Session session, @PathParam("chatId") String chatId) {
        log.info("WebSocket 连接已建立, chatId={}", chatId);
        session.getUserProperties().put("chatId", chatId);
        SESSION_MAP.put(chatId, session);
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        try {
            String chatId = (String) session.getUserProperties().get("chatId");
            Communication communication = objectMapper.readValue(message, Communication.class);
            String conversationUUID = communication.getUuid();

            switch (communication.getType()) {
                case "text":
                    log.debug("收到消息: {}", message);
                    session.getUserProperties().put("conversationUUID", conversationUUID);
                    handleTextMessage((String) communication.getData(), chatId, conversationUUID);
                    break;
                case "over":
                    if (conversationUUID == null || conversationUUID.isEmpty()) {
                        log.warn("打断请求缺少 conversationUUID");
                        break;
                    }
                    cancelStream(conversationUUID);
                    break;
                case "ping":
                    sendResponse(chatId, "pong", "", "");
                    break;
                case "tools":
                    log.info("收到 Tool 回传数据, chatId={}", chatId);
                    toolAwaitingPool.completeResult(chatId, objectMapper.writeValueAsString(communication.getData()));
                    break;
                default:
                    log.warn("未知消息类型: {}", communication.getType());
                    break;
            }

        } catch (Exception e) {
            log.error("消息处理异常: {}", e.getMessage(), e);
        }
    }

    @OnClose
    public void onClose(Session session) {
        String chatId = (String) session.getUserProperties().get("chatId");
        String conversationUUId = (String) session.getUserProperties().get("conversationUUID");
        log.info("WebSocket 连接关闭, chatId={}", chatId);
        if (chatId != null) {
            SESSION_MAP.remove(chatId);
            sendResponse(chatId, "stop", "", conversationUUId);
            cancelStream(conversationUUId);
        }
    }

    /** 取消流式生成，通过 dispose() 触发 Flux 的 doOnCancel 回调 */
    private void cancelStream(String conversationId) {
        if (conversationId == null) return;
        Disposable disposable = ACTIVE_STREAMS.get(conversationId);
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
            log.info("已取消流式生成, conversationId={}", conversationId);
        }
    }

    /**
     * 处理用户文本消息：启动 AI 流式回复
     *
     * 流程：清理旧流 → 通过 ChatService 获取 Flux → 订阅并推送每块数据 → 管理生命周期
     */
    private void handleTextMessage(String message, String chatId, String conversationUUId) {
        // 防止同一个 chatId 产生多个流，先清理旧的
        String oldConversationUUId = ACTIVE_CONVERSATION_MAP.get(chatId);
        if (oldConversationUUId != null && !oldConversationUUId.equals(conversationUUId)) {
            cancelStream(oldConversationUUId);
            log.info("发现旧对话流，已取消: {}", oldConversationUUId);
        }
        cancelStream(conversationUUId);

        ACTIVE_CONVERSATION_MAP.put(chatId, conversationUUId);

        ChatClient.StreamResponseSpec streamResponseSpec = chatService.getStreamResponseSpec(chatId, message);
        Flux<ChatResponse> chatResponseFlux = streamResponseSpec.chatResponse();

        Disposable disposable = chatResponseFlux
                .doOnNext(chatResponse -> {
                    if (chatResponse.getResult().getOutput() instanceof DeepSeekAssistantMessage) {
                        DeepSeekAssistantMessage assistantMessage =
                                (DeepSeekAssistantMessage) chatResponse.getResult().getOutput();

                        // 推送思考链（reasoning_content）
                        String thinking = assistantMessage.getReasoningContent();
                        if (thinking != null) {
                            sendResponse(chatId, "thinking", thinking, conversationUUId);
                        }

                        // 推送文本回复（content）
                        String content = assistantMessage.getText();
                        if (content != null) {
                            sendResponse(chatId, "text", content, conversationUUId);
                        }
                    } else {
                        log.warn("收到非 DeepSeek 消息类型: {}",
                                chatResponse.getResult().getOutput().getClass().getName());
                    }
                })
                .doOnError(error -> {
                    log.error("流式生成异常, chatId={}: {}", chatId, error.getMessage(), error);
                    sendResponse(chatId, "error", "AI 回复异常: " + error.getMessage(), conversationUUId);
                })
                .doOnComplete(() -> {
                    log.info("流式生成正常完成, chatId={}", chatId);
                    sendResponse(chatId, "over", null, conversationUUId);
                })
                .doOnCancel(() -> {
                    log.info("流式生成被用户打断, chatId={}", chatId);
                    sendResponse(chatId, "over", "", conversationUUId);
                })
                .doFinally(signalType -> {
                    String currentActiveUuid = ACTIVE_CONVERSATION_MAP.get(chatId);
                    if (conversationUUId.equals(currentActiveUuid)) {
                        ACTIVE_CONVERSATION_MAP.remove(chatId);
                    }
                    ACTIVE_STREAMS.remove(conversationUUId);
                })
                .subscribe();

        ACTIVE_STREAMS.put(conversationUUId, disposable);
    }

    /** 通过 WebSocket 向前端发送 JSON 消息（线程安全） */
    public static void sendResponse(String chatId, String type, String data, String conversationUUID) {
        Session session = getSession(chatId);
        if (session == null || !session.isOpen()) {
            log.warn("WebSocket 会话不可用, chatId={}, 跳过消息", chatId);
            return;
        }
        try {
            Communication response = new Communication();
            response.setType(type);
            response.setData(data);
            response.setUuid(conversationUUID);
            String jsonResponse = objectMapper.writeValueAsString(response);

            synchronized (session) {
                session.getBasicRemote().sendText(jsonResponse);
            }
        } catch (Exception e) {
            log.error("发送 WebSocket 消息失败, chatId={}: {}", chatId, e.getMessage(), e);
        }
    }

    public static Session getSession(String chatId) {
        return SESSION_MAP.get(chatId);
    }
}
