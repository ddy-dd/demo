package com.example.demo.ai.db;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 数据库表初始化器
 * <p>
 * 应用启动时自动创建所需的 SQLite 表结构。
 * 使用 CREATE TABLE IF NOT EXISTS 确保重复执行安全。
 * </p>
 */
@Slf4j
@Component
public class DatabaseInitializer {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        createConversationsTable();
        createChatMessagesTable();
        createSkillRecordsTable();
        createKnowledgeFileRecordsTable();
        log.info("SQLite 数据库表初始化完成");
    }

    /** 对话记录表 */
    private void createConversationsTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT PRIMARY KEY,
                title TEXT DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
        """);
    }

    /** 对话消息表 */
    private void createChatMessagesTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS chat_messages (
                id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                thinking TEXT DEFAULT '',
                created_at TEXT NOT NULL,
                FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
            )
        """);
    }

    /** Skill 上传记录表（仅存元数据，不含文件内容） */
    private void createSkillRecordsTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS skill_records (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                package_name TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'success',
                created_at TEXT NOT NULL
            )
        """);
    }

    /** 知识库文件记录表（仅存元数据，不含文件内容） */
    private void createKnowledgeFileRecordsTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS knowledge_file_records (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                size INTEGER DEFAULT 0,
                path TEXT DEFAULT '',
                status TEXT NOT NULL DEFAULT 'success',
                created_at TEXT NOT NULL
            )
        """);
        // 兼容已有数据库：如果缺少 path 列就加上
        try {
            jdbcTemplate.execute("ALTER TABLE knowledge_file_records ADD COLUMN path TEXT DEFAULT ''");
        } catch (Exception ignored) {
            // 列已存在，忽略
        }
    }
}
