package com.example.demo.AI.ServiceImpl;


import com.example.demo.AI.Object.Communication;
import com.example.demo.AI.Pool.ToolAwaitingPoolByCompletableFuture;
import com.example.demo.AI.ServiceImpl.Service.ChatService;
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

@ServerEndpoint(value = "/websocket/{chatId}")
@Slf4j
@Service
public class WebsocketService {
    //private static ChatService chatService;
    private static ChatService chatService;
    private static ApplicationContext applicationContext;
    private static ToolAwaitingPoolByCompletableFuture toolAwaitingPool;

    private static final ConcurrentHashMap<String, Session> SESSION_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Disposable> ACTIVE_STREAMS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> ACTIVE_CONVERSATION_MAP = new ConcurrentHashMap<>();

//    private final ChatService chatService;
//    private final ToolAwaitingPoolByCompletableFuture toolAwaitingPool;

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
        log.info("WebSocket connection opened.");
        session.getUserProperties().put("chatId", chatId);
        SESSION_MAP.put(chatId, session);
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        log.info("Received message: {}", message);
        // Process the message and generate a response
        //String response = "Hello" + "!";
        try {
            String chatId = (String) session.getUserProperties().get("chatId");
            log.info("chatId: {}", chatId);

            ObjectMapper objectMapper = new ObjectMapper();
            Communication communication = objectMapper.readValue(message, Communication.class);
            String conversationUUID = communication.getUuid();
            switch (communication.getType()){
                case "text":
                    session.getUserProperties().put("conversationUUID", conversationUUID);
                    handleTextMessage((String) communication.getData(), chatId, conversationUUID);
                    break;
                case "over":
                    if(conversationUUID == null || conversationUUID.isEmpty()){
                        log.warn("conversationUUID is null or empty");
                        break;
                    }
                    cancelStream(conversationUUID);
                    break;
                case "ping":
                    sendResponse(chatId,"pong","","");
                    break;
                case "tools":
                    System.out.println("tools");
                    toolAwaitingPool.completeResult(chatId, objectMapper.writeValueAsString(communication.getData()));
                default:
                    break;
            }

        } catch (Exception e) {
            log.error("Error sending response: {}", e.getMessage());
        }
    }

    @OnClose
    public void onClose(Session session) {
        String chatId = (String) session.getUserProperties().get("chatId");
        String conversationUUId = (String) session.getUserProperties().get("conversationUUID");
        sendResponse(chatId, "stop", "", conversationUUId);
        log.info("WebSocket connection closed.");
        if(chatId != null){
            SESSION_MAP.remove(chatId);
            cancelStream(conversationUUId);
        }

    }

    private void cancelStream(String conversationId) {
        Disposable disposable = ACTIVE_STREAMS.get(conversationId);
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose(); // 这会触发 Flux 的 doOnCancel 回调
            log.info("Manually disposed stream for chatId: {}", conversationId);
        }
    }

    private void handleTextMessage(String message, String chatId, String conversationUUId) {
        // 防止同一个 chatId 产生多个流，先清理旧的
        String oldConversationUUId = ACTIVE_CONVERSATION_MAP.get(chatId);
        if(oldConversationUUId != null && !oldConversationUUId.equals(conversationUUId)){
            cancelStream(oldConversationUUId);
            log.info("哈哈哈哈哈Cancel stream for chatId: {}", oldConversationUUId);
        }
            cancelStream(conversationUUId);

        ACTIVE_CONVERSATION_MAP.put(chatId, conversationUUId);

        ChatClient.StreamResponseSpec streamResponseSpec = chatService.getStreamResponseSpec(chatId, message);
        Flux<ChatResponse> chatResponseFlux = streamResponseSpec.chatResponse();

        Disposable disposable = chatResponseFlux
                .doOnNext(chatResponse -> {
                    if (chatResponse.getResult().getOutput() instanceof DeepSeekAssistantMessage) {
                        DeepSeekAssistantMessage assistantMessage = (DeepSeekAssistantMessage) chatResponse.getResult().getOutput();
                        // 发送 thinking 内容
                        String thinking = assistantMessage.getReasoningContent();
                        if (thinking != null) sendResponse(chatId, "thinking", thinking, conversationUUId);

                        // 发送 text 内容
                        String content = assistantMessage.getText();
                        if (content != null) sendResponse(chatId, "text", content, conversationUUId);
                    } else {
                        log.warn("收到非 DeepSeek 消息类型: {}", chatResponse.getResult().getOutput().getClass().getName());
                    }
                })
                .doOnError(error -> {
                    log.error("Error in flux stream for chatId {}: {}", chatId, error.getMessage(), error);
                    sendResponse(chatId, "error", error.getMessage(), conversationUUId);
                })
                .doOnComplete(() -> {
                    log.info("Stream completed normally for chatId: {}", chatId);
                    sendResponse(chatId, "over", null, conversationUUId);
                    // 正常完成后执行事务
                })
                .doOnCancel(() -> {
                    // 【关键】当流被主动打断时触发
                    log.info("Stream cancelled by user for chatId: {}", chatId);
                    sendResponse(chatId, "over", "", conversationUUId);
                    // 在中断时同样需要执行事务（例如保存已生成的部分文本）
                })
                .doFinally(signalType -> {
                    // 无论完成、错误还是取消，最后都从 Map 中移除
                    String currentActiveUuid = ACTIVE_CONVERSATION_MAP.get(chatId);
                    if (conversationUUId.equals(currentActiveUuid)) {
                        ACTIVE_CONVERSATION_MAP.remove(chatId);
                    }
                    ACTIVE_STREAMS.remove(conversationUUId);
                })
                .subscribe();

        // 将当前的订阅句柄存入 Map
        ACTIVE_STREAMS.put(conversationUUId, disposable);
    }

    public static void sendResponse(String chatId, String type, String data,String conversationUUID) {
        Session session = getSession(chatId);
        if (session == null || !session.isOpen()) {
            log.warn("Session is null or closed for chatId: {}, skipping message.", chatId);
            return;
        }
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            Communication response = new Communication();
            response.setType(type);
            response.setData(data);
            response.setUuid(conversationUUID);
            String jsonResponse = objectMapper.writeValueAsString(response);

            synchronized (session) {
                session.getBasicRemote().sendText(jsonResponse);
            }
        } catch (Exception e) {
            log.error("Error sending response safely to chatId: {}: {}", chatId, e.getMessage(), e);
        }
    }

    public static Session getSession(String chatId){
        return SESSION_MAP.get(chatId);
    }
}
