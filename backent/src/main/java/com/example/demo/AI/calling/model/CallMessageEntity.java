package com.example.demo.ai.calling.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * 通话消息实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CallMessageEntity {
    private String id;
    private String callId;
    private String role;        // 'user' / 'assistant'
    private String content;
    private String asrSegments; // JSON
    private Integer seq;
    private String createdAt;
}
