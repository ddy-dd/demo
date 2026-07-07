package com.example.demo.ai.calling.service;

import com.example.demo.ai.asr.AsrService;
import com.example.demo.ai.calling.dao.CallRecordDao;
import com.example.demo.ai.calling.model.CallMessageEntity;
import com.example.demo.ai.calling.model.CallCommunication;
import com.example.demo.ai.chat.service.ChatService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通话 WebSocket 服务端 —— 流式语音通话
 *
 * <p>每轮：前端录 3s 音频 → 二进制发送 → audio_end 结束标记
 * → ASR 转写 → AI 回复 → 继续下一轮，长期保持连接。</p>
 */
@ServerEndpoint(value = "/calling/{callId}")
@Slf4j
@Service
public class CallingWebsocketService {

    private static ApplicationContext applicationContext;
    private static ChatService chatService;
    private static AsrService asrService;
    private static CallRecordDao callRecordDao;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final ConcurrentHashMap<String, Session> SESSION_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ByteArrayOutputStream> SEGMENT_BUFFERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicInteger> SEQ_MAP = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        chatService = applicationContext.getBean(ChatService.class);
        asrService = applicationContext.getBean(AsrService.class);
        callRecordDao = applicationContext.getBean(CallRecordDao.class);
    }

    @Autowired
    public void setApplicationContext(ApplicationContext ctx) {
        applicationContext = ctx;
    }

    private void sendJson(String callId, String type, Object data) {
        Session session = SESSION_MAP.get(callId);
        if (session == null || !session.isOpen()) return;
        try {
            var msg = new CallCommunication();
            msg.setType(type);
            msg.setData(data);
            msg.setCallId(callId);
            session.getBasicRemote().sendText(objectMapper.writeValueAsString(msg));
        } catch (Exception e) {
            log.warn("[{}] 发送消息失败: {}", callId, e.getMessage());
        }
    }

    @OnOpen
    public void onOpen(Session session, @PathParam("callId") String callId) {
        SESSION_MAP.put(callId, session);
        SEGMENT_BUFFERS.put(callId, new ByteArrayOutputStream());
        SEQ_MAP.put(callId, new AtomicInteger(0));

        if (!callRecordDao.callExists(callId)) {
            callRecordDao.createCall(callId);
        }

        sendJson(callId, "call_started", Map.of("callId", callId));
        log.info("[{}] 通话连接已建立", callId);
    }

    /** 接收二进制音频帧，追加到当前分段缓冲区 */
    @OnMessage(maxMessageSize = 1024 * 1024)
    public void onBinaryMessage(byte[] data, Session session, @PathParam("callId") String callId) {
        ByteArrayOutputStream buf = SEGMENT_BUFFERS.get(callId);
        if (buf != null) {
            try {
                buf.write(data);
            } catch (IOException e) {
                log.warn("[{}] 写入音频缓存失败: {}", callId, e.getMessage());
            }
        }
    }

    @OnMessage
    public void onTextMessage(String text, Session session, @PathParam("callId") String callId) {
        CallCommunication msg;
        try {
            msg = objectMapper.readValue(text, CallCommunication.class);
        } catch (JsonProcessingException e) {
            return;
        }

        switch (msg.getType()) {
            case "ping":
                sendJson(callId, "pong", null);
                break;

            case "audio_end":
                processSegment(callId);
                break;

            default:
                log.debug("[{}] 未知消息类型: {}", callId, msg.getType());
        }
    }

    /** 处理一段完整的音频：ASR → AI → 入库 */
    private void processSegment(String callId) {
        ByteArrayOutputStream buf = SEGMENT_BUFFERS.get(callId);
        if (buf == null || buf.size() == 0) return;

        byte[] audioData = buf.toByteArray();
        buf.reset(); // 重置缓冲区，准备下一段

        // 1. ASR
        String asrText;
        try {
            sendJson(callId, "asr_partial", Map.of("text", "正在识别..."));
            var result = asrService.transcribe(audioData, "segment.webm");
            asrText = result != null ? result.text() : "";
        } catch (Exception e) {
            log.warn("[{}] ASR 识别无结果: {}", callId, e.getMessage());
            return;
        }

        if (asrText.isBlank()) return;

        sendJson(callId, "asr_final", Map.of("text", asrText));

        // 2. 保存用户消息
        int seq = SEQ_MAP.get(callId).incrementAndGet();
        String now = LocalDateTime.now().format(DTF);
        callRecordDao.insertMessage(new CallMessageEntity(
            UUID.randomUUID().toString(), callId, "user", asrText, "", seq, now
        ));

        // 3. AI agent 回复
        try {
            var flux = chatService.generation(callId, asrText);
            var responseBuf = new StringBuilder();

            flux.subscribe(
                chunk -> {
                    responseBuf.append(chunk);
                    sendJson(callId, "agent_response", Map.of("text", chunk));
                },
                err -> log.error("[{}] AI 回复异常: {}", callId, err.getMessage()),
                () -> {
                    String aiText = responseBuf.toString();
                    if (!aiText.isBlank()) {
                        int aiSeq = SEQ_MAP.get(callId).incrementAndGet();
                        callRecordDao.insertMessage(new CallMessageEntity(
                            UUID.randomUUID().toString(), callId, "assistant",
                            aiText, "", aiSeq,
                            LocalDateTime.now().format(DTF)
                        ));
                    }
                    sendJson(callId, "agent_done", null);
                    log.info("[{}] 本轮对话完成", callId);
                }
            );
        } catch (Exception e) {
            log.error("[{}] AI 调用失败: {}", callId, e.getMessage());
        }
    }

    @OnClose
    public void onClose(Session session, @PathParam("callId") String callId) {
        SESSION_MAP.remove(callId);
        SEGMENT_BUFFERS.remove(callId);
        SEQ_MAP.remove(callId);
        callRecordDao.updateCallStatus(callId, "completed", 0L);
        log.info("[{}] 通话连接已关闭", callId);
    }

    @OnError
    public void onError(Session session, Throwable error, @PathParam("callId") String callId) {
        log.error("[{}] 通话异常: {}", callId, error.getMessage());
        SESSION_MAP.remove(callId);
        SEGMENT_BUFFERS.remove(callId);
    }
}
