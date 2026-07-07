package com.example.demo.ai.chat.service;

import com.example.demo.ai.chat.dao.ConversationDao;
import com.example.demo.ai.chat.model.ChatMessageEntity;
import com.example.demo.ai.chat.model.Communication;
import com.example.demo.ai.tools.pool.ToolAwaitingPoolByCompletableFuture;
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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
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
 * 挂起 AI 线程等待回传。详见 {@link com.example.demo.ai.tools.pool.ToolAwaitingPoolByCompletableFuture}
 */
@ServerEndpoint(value = "/websocket/{chatId}")
@Slf4j
@Service
public class ChatWebsocketService {

    private static ChatService chatService;
    private static ApplicationContext applicationContext;
    private static ToolAwaitingPoolByCompletableFuture toolAwaitingPool;
    private static ConversationDao conversationDao;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 会话映射：chatId → WebSocket Session */
    private static final ConcurrentHashMap<String, Session> SESSION_MAP = new ConcurrentHashMap<>();

    /** 活跃流映射：conversationUUID → Disposable（用于打断） */
    private static final ConcurrentHashMap<String, Disposable> ACTIVE_STREAMS = new ConcurrentHashMap<>();

    /** 当前对话映射：chatId → conversationUUID（用于去重） */
    private static final ConcurrentHashMap<String, String> ACTIVE_CONVERSATION_MAP = new ConcurrentHashMap<>();

    /** 正在构建的助理消息：conversationUUID → {assistantMessageId, thinkingBuilder, textBuilder} */
    private static final ConcurrentHashMap<String, InProgressMessage> IN_PROGRESS_MESSAGES = new ConcurrentHashMap<>();

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 保存当前正在流式构建的消息 */
    private record InProgressMessage(String messageId, StringBuilder thinkingBuilder, StringBuilder textBuilder) {}

    @PostConstruct
    public void init() {
        chatService = applicationContext.getBean(ChatService.class);
        toolAwaitingPool = applicationContext.getBean(ToolAwaitingPoolByCompletableFuture.class);
        conversationDao = applicationContext.getBean(ConversationDao.class);
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
     * 流程：清理旧流 → 保存用户消息到数据库 → 通过 ChatService 获取 Flux → 流式订阅
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

        // ── 保存用户消息到数据库 ────────────────────────────────────────
        String now = LocalDateTime.now().format(DTF);
        try {
            if (!conversationDao.conversationExists(chatId)) {
                conversationDao.createConversation(chatId, "");
            }
            String userMessageId = UUID.randomUUID().toString();
            ChatMessageEntity userMsg = new ChatMessageEntity(
                userMessageId, chatId, "user", message, "", now
            );
            conversationDao.insertMessage(userMsg);
        } catch (Exception e) {
            log.error("保存用户消息到数据库失败, chatId={}: {}", chatId, e.getMessage());
        }

        // ── 准备保存助理回复 ─────────────────────────────────────────────
        String assistantMessageId = UUID.randomUUID().toString();
        IN_PROGRESS_MESSAGES.put(conversationUUId,
            new InProgressMessage(assistantMessageId, new StringBuilder(), new StringBuilder()));

        ChatClient.StreamResponseSpec streamResponseSpec = chatService.getStreamResponseSpec(chatId, message);
        Flux<ChatResponse> chatResponseFlux = streamResponseSpec.chatResponse();

        Disposable disposable = chatResponseFlux
                .doOnNext(chatResponse -> {
                    if (chatResponse.getResult().getOutput() instanceof DeepSeekAssistantMessage) {
                        DeepSeekAssistantMessage assistantMessage =
                                (DeepSeekAssistantMessage) chatResponse.getResult().getOutput();

                        // 累积思考链
                        String thinking = assistantMessage.getReasoningContent();
                        if (thinking != null) {
                            InProgressMessage ipm = IN_PROGRESS_MESSAGES.get(conversationUUId);
                            if (ipm != null) ipm.thinkingBuilder().append(thinking);
                            sendResponse(chatId, "thinking", thinking, conversationUUId);
                        }

                        // 累积文本回复
                        String content = assistantMessage.getText();
                        if (content != null) {
                            InProgressMessage ipm = IN_PROGRESS_MESSAGES.get(conversationUUId);
                            if (ipm != null) ipm.textBuilder().append(content);
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
                    saveAssistantMessage(chatId, conversationUUId, assistantMessageId, now);
                })
                .doOnCancel(() -> {
                    log.info("流式生成被用户打断, chatId={}", chatId);
                    sendResponse(chatId, "over", "", conversationUUId);
                    saveAssistantMessage(chatId, conversationUUId, assistantMessageId, now);
                })
                .doFinally(signalType -> {
                    String currentActiveUuid = ACTIVE_CONVERSATION_MAP.get(chatId);
                    if (conversationUUId.equals(currentActiveUuid)) {
                        ACTIVE_CONVERSATION_MAP.remove(chatId);
                    }
                    ACTIVE_STREAMS.remove(conversationUUId);
                    IN_PROGRESS_MESSAGES.remove(conversationUUId);
                })
                .subscribe();

        ACTIVE_STREAMS.put(conversationUUId, disposable);
    }

    /** 保存流式累积的助理消息到数据库 */
    private void saveAssistantMessage(String chatId, String conversationUUId,
                                       String assistantMessageId, String now) {
        try {
            InProgressMessage ipm = IN_PROGRESS_MESSAGES.get(conversationUUId);
            if (ipm == null) return;
            ChatMessageEntity assistantMsg = new ChatMessageEntity(
                assistantMessageId, chatId, "assistant",
                ipm.textBuilder().toString(),
                ipm.thinkingBuilder().toString(),
                now
            );
            conversationDao.insertMessage(assistantMsg);
            log.debug("保存助理消息到数据库, conversationUUId={}", conversationUUId);
        } catch (Exception e) {
            log.error("保存助理消息到数据库失败, chatId={}: {}", chatId, e.getMessage());
        }
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
