package com.example.demo.ai.skills.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * Skill 记录实体（含技能正文和解析后的元数据）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SkillRecordEntity {
    private String id;
    private String name;         // 文件名（如 "stop-slop.md"）
    private String packageName;  // 包名（目录名）
    private String description;  // 从 SKILL.md frontmatter 自动解析
    private String rawContent;   // 完整 SKILL.md 内容
    private int isSystem;        // 1=系统内置，0=用户上传
    private String status;       // success / error
    private String createdAt;
}
