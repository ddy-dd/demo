package com.example.demo.ai.chat.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * 数据库聊天消息实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageEntity {
    private String id;
    private String conversationId;
    private String role;    // 'user' or 'assistant'
    private String content;
    private String thinking;  // DeepSeek reasoning_content（仅 assistant 消息）
    private String createdAt;
}
