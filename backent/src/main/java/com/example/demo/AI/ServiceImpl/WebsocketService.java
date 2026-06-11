package com.example.demo.AI.ServiceImpl;


import com.example.demo.AI.Object.Communication;
import com.example.demo.AI.ServiceImpl.Service.ChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@ServerEndpoint(value = "/websocket/{chatId}")
@Component
@Slf4j
public class WebsocketService {
    //private static ChatService chatService;
    private static ChatService chatService;
    private static ApplicationContext applicationContext;


    @PostConstruct
    public void init() {
        chatService = applicationContext.getBean(ChatService.class);
    }

    @Autowired
    public void setApplicationContext(ApplicationContext ctx) {
        applicationContext = ctx;
    }

    @OnOpen
    public void onOpen(Session session, @PathParam("chatId") String chatId) {
        log.info("WebSocket connection opened.");
        session.getUserProperties().put("chatId", chatId);
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        log.info("Received message: {}", message);
        // Process the message and generate a response
        //String response = "Hello" + "!";
        try {
            String chatId = (String) session.getUserProperties().get("chatId");
            log.info("chatId: {}", chatId);
            Communication response = new Communication();
            ObjectMapper objectMapper = new ObjectMapper();
            Communication communication = objectMapper.readValue(message, Communication.class);
            switch (communication.getType()){
                case "text":
                    handleTextMessage(session,communication.getData(),chatId);
                    break;
                case "stop":
                    this.onClose(session);
                    sendResponse(session,"stop","");
                    break;
                case "ping":
                    sendResponse(session,"pong","");
                    break;
                default:
                    break;
            }

        } catch (Exception e) {
            log.error("Error sending response: {}", e.getMessage());
        }
    }

    @OnClose
    public void onClose(Session session) {
        log.info("WebSocket connection closed.");
    }

    private void handleTextMessage(Session session,String message, String chatId) {
        //Flux<String> flux = chatService.generation(chatId,message);
        ChatClient.StreamResponseSpec streamResponseSpec = chatService.getStreamResponseSpec(chatId, message);
//        Flux<String> flux = streamResponseSpec.content();
//        flux.subscribe(
//                chunk -> {
//                    try {
//                        sendResponse(session, "text", chunk);
//                    } catch (Exception e) {
//                        log.error("Error sending chunk: {}", e.getMessage(), e);
//                    }
//                },
//                error -> {
//                    log.error("Error in flux stream: {}", error.getMessage(), error);
//                    try {
//                        sendResponse(session, "error", error.getMessage());
//                    } catch (Exception e) {
//                        log.error("Error sending error response: {}", e.getMessage(), e);
//                    }
//                },
//                () -> {
//                    log.info("Stream completed for chatId: {}", chatId);
//                }
//        );

        Flux<ChatResponse> chatResponseFlux = streamResponseSpec.chatResponse();
        chatResponseFlux.subscribe(
                chatResponse -> {
                    DeepSeekAssistantMessage assistantMessage = (DeepSeekAssistantMessage) chatResponse.getResult().getOutput();
                    String thinking = assistantMessage.getReasoningContent();
                    if (thinking != null){
                        try {
                            sendResponse(session, "thinking", thinking);
                        } catch (Exception e) {
                            log.error("Error sending chunk: {}", e.getMessage(), e);
                        }
                    }
                    String content = assistantMessage.getText();
                    if (content != null){
                        try {
                            sendResponse(session, "text", content);
                        } catch (Exception e) {
                            log.error("Error sending chunk: {}", e.getMessage(), e);
                        }
                    }
                },
                error -> {
                    log.error("Error in chat response stream: {}", error.getMessage(), error);
                }
        );


    }

    private void sendResponse(Session session, String type, String data) throws Exception {
        if (session == null || !session.isOpen()) {
            log.warn("Session is null or closed");
            return;
        }

        ObjectMapper objectMapper = new ObjectMapper();
        Communication response = new Communication();
        response.setType(type);
        response.setData(data);

        String jsonResponse = objectMapper.writeValueAsString(response);
        session.getBasicRemote().sendText(jsonResponse);
    }
}
