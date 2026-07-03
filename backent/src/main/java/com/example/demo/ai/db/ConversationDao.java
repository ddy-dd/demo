package com.example.demo.ai.db;

import com.example.demo.ai.db.model.ChatMessageEntity;
import com.example.demo.ai.db.model.ConversationEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 对话 DAO — 管理对话记录和消息的持久化
 */
@Repository
public class ConversationDao {

    private final JdbcTemplate jdbcTemplate;
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ConversationDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── 对话 CRUD ────────────────────────────────────────────

    /** 创建新对话 */
    public void createConversation(String id, String title) {
        String now = LocalDateTime.now().format(DTF);
        jdbcTemplate.update(
            "INSERT INTO conversations (id, title, created_at, updated_at) VALUES (?, ?, ?, ?)",
            id, title, now, now
        );
    }

    /** 获取所有对话，按更新时间倒序 */
    public List<ConversationEntity> listConversations() {
        return jdbcTemplate.query(
            "SELECT c.*, (SELECT COUNT(*) FROM chat_messages WHERE conversation_id = c.id) as message_count " +
            "FROM conversations c ORDER BY c.updated_at DESC",
            new ConversationRowMapper()
        );
    }

    /** 获取单条对话 */
    public ConversationEntity getConversation(String id) {
        List<ConversationEntity> results = jdbcTemplate.query(
            "SELECT c.*, (SELECT COUNT(*) FROM chat_messages WHERE conversation_id = c.id) as message_count " +
            "FROM conversations c WHERE c.id = ?",
            new ConversationRowMapper(), id
        );
        return results.isEmpty() ? null : results.get(0);
    }

    /** 更新对话标题 */
    public void updateConversationTitle(String id, String title) {
        String now = LocalDateTime.now().format(DTF);
        jdbcTemplate.update(
            "UPDATE conversations SET title = ?, updated_at = ? WHERE id = ?",
            title, now, id
        );
    }

    /** 删除对话及其所有消息（外键级联） */
    public void deleteConversation(String id) {
        jdbcTemplate.update("DELETE FROM chat_messages WHERE conversation_id = ?", id);
        jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", id);
    }

    /** 对话是否存在 */
    public boolean conversationExists(String id) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM conversations WHERE id = ?", Integer.class, id
        );
        return count != null && count > 0;
    }

    // ── 消息 CRUD ────────────────────────────────────────────

    /** 插入一条消息 */
    public void insertMessage(ChatMessageEntity msg) {
        String now = (msg.getCreatedAt() != null && !msg.getCreatedAt().isEmpty())
            ? msg.getCreatedAt() : LocalDateTime.now().format(DTF);
        jdbcTemplate.update(
            "INSERT INTO chat_messages (id, conversation_id, role, content, thinking, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
            msg.getId(), msg.getConversationId(), msg.getRole(),
            msg.getContent(), msg.getThinking(), now
        );
    }

    /** 获取某个对话的所有消息，按时间正序 */
    public List<ChatMessageEntity> getMessages(String conversationId) {
        return jdbcTemplate.query(
            "SELECT * FROM chat_messages WHERE conversation_id = ? ORDER BY created_at ASC",
            new ChatMessageRowMapper(), conversationId
        );
    }

    /** 更新消息内容（用于完成流式写入最终内容） */
    public void updateMessageContent(String messageId, String content, String thinking) {
        jdbcTemplate.update(
            "UPDATE chat_messages SET content = ?, thinking = ? WHERE id = ?",
            content, thinking, messageId
        );
    }

    // ── RowMapper ────────────────────────────────────────────

    private static class ConversationRowMapper implements RowMapper<ConversationEntity> {
        @Override
        public ConversationEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            ConversationEntity c = new ConversationEntity();
            c.setId(rs.getString("id"));
            c.setTitle(rs.getString("title"));
            c.setCreatedAt(rs.getString("created_at"));
            c.setUpdatedAt(rs.getString("updated_at"));
            c.setMessageCount(rs.getInt("message_count"));
            return c;
        }
    }

    private static class ChatMessageRowMapper implements RowMapper<ChatMessageEntity> {
        @Override
        public ChatMessageEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ChatMessageEntity(
                rs.getString("id"),
                rs.getString("conversation_id"),
                rs.getString("role"),
                rs.getString("content"),
                rs.getString("thinking"),
                rs.getString("created_at")
            );
        }
    }
}
