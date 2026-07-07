package com.example.demo.ai.chat.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * 数据库对话实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationEntity {
    private String id;
    private String title;
    private String createdAt;
    private String updatedAt;
    private int messageCount; // 非 DB 字段，用于列表显示
}
