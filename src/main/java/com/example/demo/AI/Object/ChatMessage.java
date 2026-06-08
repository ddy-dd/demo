package com.example.demo.AI.Object;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ChatMessage {
    private String role;
    private String content;
    private LocalDateTime time;

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
        this.time = LocalDateTime.now();
    }
}
