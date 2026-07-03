package com.example.demo.ai.db.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * Skill 上传记录实体（仅存元数据，不含文件内容）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillRecordEntity {
    private String id;
    private String name;         // 文件名
    private String packageName;  // 包名
    private String status;       // success / error
    private String createdAt;
}
