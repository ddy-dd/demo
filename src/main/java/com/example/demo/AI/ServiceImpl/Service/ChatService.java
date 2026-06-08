package com.example.demo.AI.ServiceImpl.Service;


import reactor.core.publisher.Flux;

public interface ChatService {
    Flux<String> generation(String chatId, String userInput);
}
