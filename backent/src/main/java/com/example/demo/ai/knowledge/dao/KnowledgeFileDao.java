package com.example.demo.ai.knowledge.dao;

import com.example.demo.ai.knowledge.model.KnowledgeFileEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 知识库文件记录 DAO — 管理上传的知识库文件元数据持久化
 */
@Repository
public class KnowledgeFileDao {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeFileDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 插入一条记录 */
    public void insert(KnowledgeFileEntity record) {
        jdbcTemplate.update(
            "INSERT INTO knowledge_file_records (id, name, size, path, status, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            record.getId(), record.getName(), record.getSize(),
            record.getPath(), record.getStatus(), record.getCreatedAt()
        );
    }

    /** 查询所有记录，按创建时间倒序 */
    public List<KnowledgeFileEntity> listAll() {
        return jdbcTemplate.query(
            "SELECT * FROM knowledge_file_records ORDER BY created_at DESC",
            new KnowledgeFileRowMapper()
        );
    }

    /** 删除一条记录 */
    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM knowledge_file_records WHERE id = ?", id);
    }

    private static class KnowledgeFileRowMapper implements RowMapper<KnowledgeFileEntity> {
        @Override
        public KnowledgeFileEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new KnowledgeFileEntity(
                rs.getString("id"),
                rs.getString("name"),
                rs.getLong("size"),
                rs.getString("path"),
                rs.getString("status"),
                rs.getString("created_at")
            );
        }
    }
}
