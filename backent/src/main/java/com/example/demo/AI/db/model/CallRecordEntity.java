package com.example.demo.ai.db.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * 通话记录实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CallRecordEntity {
    private String id;
    private String status;      // 'active' / 'completed' / 'cancelled'
    private Long durationMs;
    private String startedAt;
    private String endedAt;
    private String createdAt;
}
