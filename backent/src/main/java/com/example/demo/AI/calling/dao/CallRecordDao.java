package com.example.demo.ai.calling.dao;

import com.example.demo.ai.calling.model.CallMessageEntity;
import com.example.demo.ai.calling.model.CallRecordEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 通话 DAO — 管理通话记录和通话消息的持久化
 */
@Repository
public class CallRecordDao {

    private final JdbcTemplate jdbcTemplate;
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public CallRecordDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── 通话记录 CRUD ──────────────────────────────────────

    /** 创建新通话 */
    public void createCall(String id) {
        String now = LocalDateTime.now().format(DTF);
        jdbcTemplate.update(
            "INSERT INTO call_records (id, status, started_at, created_at) VALUES (?, ?, ?, ?)",
            id, "active", now, now
        );
    }

    /** 获取所有通话，按开始时间倒序 */
    public List<CallRecordEntity> listCalls() {
        return jdbcTemplate.query(
            "SELECT * FROM call_records ORDER BY started_at DESC",
            new CallRecordRowMapper()
        );
    }

    /** 获取单条通话 */
    public CallRecordEntity getCall(String id) {
        List<CallRecordEntity> results = jdbcTemplate.query(
            "SELECT * FROM call_records WHERE id = ?",
            new CallRecordRowMapper(), id
        );
        return results.isEmpty() ? null : results.get(0);
    }

    /** 更新通话状态 */
    public void updateCallStatus(String id, String status, Long durationMs) {
        String now = LocalDateTime.now().format(DTF);
        jdbcTemplate.update(
            "UPDATE call_records SET status = ?, duration_ms = ?, ended_at = ? WHERE id = ?",
            status, durationMs, now, id
        );
    }

    /** 删除通话及其所有消息 */
    public void deleteCall(String id) {
        jdbcTemplate.update("DELETE FROM call_messages WHERE call_id = ?", id);
        jdbcTemplate.update("DELETE FROM call_records WHERE id = ?", id);
    }

    /** 通话是否存在 */
    public boolean callExists(String id) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM call_records WHERE id = ?", Integer.class, id
        );
        return count != null && count > 0;
    }

    // ── 通话消息 CRUD ──────────────────────────────────────

    /** 插入一条通话消息 */
    public void insertMessage(CallMessageEntity msg) {
        String now = (msg.getCreatedAt() != null && !msg.getCreatedAt().isEmpty())
            ? msg.getCreatedAt() : LocalDateTime.now().format(DTF);
        jdbcTemplate.update(
            "INSERT INTO call_messages (id, call_id, role, content, asr_segments, seq, created_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            msg.getId(), msg.getCallId(), msg.getRole(),
            msg.getContent(), msg.getAsrSegments(), msg.getSeq(), now
        );
    }

    /** 获取某个通话的所有消息，按序号正序 */
    public List<CallMessageEntity> getMessages(String callId) {
        return jdbcTemplate.query(
            "SELECT * FROM call_messages WHERE call_id = ? ORDER BY seq ASC",
            new CallMessageRowMapper(), callId
        );
    }

    /** 获取通话消息总数 */
    public int getMessageCount(String callId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM call_messages WHERE call_id = ?", Integer.class, callId
        );
        return count != null ? count : 0;
    }

    // ── RowMapper ──────────────────────────────────────────

    private static class CallRecordRowMapper implements RowMapper<CallRecordEntity> {
        @Override
        public CallRecordEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            CallRecordEntity c = new CallRecordEntity();
            c.setId(rs.getString("id"));
            c.setStatus(rs.getString("status"));
            c.setDurationMs(rs.getLong("duration_ms"));
            c.setStartedAt(rs.getString("started_at"));
            c.setEndedAt(rs.getString("ended_at"));
            c.setCreatedAt(rs.getString("created_at"));
            return c;
        }
    }

    private static class CallMessageRowMapper implements RowMapper<CallMessageEntity> {
        @Override
        public CallMessageEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CallMessageEntity(
                rs.getString("id"),
                rs.getString("call_id"),
                rs.getString("role"),
                rs.getString("content"),
                rs.getString("asr_segments"),
                rs.getInt("seq"),
                rs.getString("created_at")
            );
        }
    }
}
