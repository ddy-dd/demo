package com.example.demo.ai.chat.service;


import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

public interface ChatService {
    Flux<String> generation(String chatId, String userInput);
    ChatClient.StreamResponseSpec getStreamResponseSpec(String chatId, String userInput);
}
