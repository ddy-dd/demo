package com.example.demo.ai.db.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * 知识库文件记录实体（仅存元数据，不含文件内容）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeFileEntity {
    private String id;
    private String name;     // 文件名
    private long size;       // 文件大小（字节）
    private String path;     // 后端磁盘上的文件路径
    private String status;   // success / error
    private String createdAt;
}
