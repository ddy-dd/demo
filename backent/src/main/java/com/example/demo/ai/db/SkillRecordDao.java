package com.example.demo.ai.db;

import com.example.demo.ai.db.model.SkillRecordEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Skill 记录 DAO — 管理上传的 Skill 元数据持久化
 */
@Repository
public class SkillRecordDao {

    private final JdbcTemplate jdbcTemplate;

    public SkillRecordDao(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 插入一条记录 */
    public void insert(SkillRecordEntity record) {
        jdbcTemplate.update(
            "INSERT INTO skill_records (id, name, package_name, status, created_at) VALUES (?, ?, ?, ?, ?)",
            record.getId(), record.getName(), record.getPackageName(),
            record.getStatus(), record.getCreatedAt()
        );
    }

    /** 查询所有记录，按创建时间倒序 */
    public List<SkillRecordEntity> listAll() {
        return jdbcTemplate.query(
            "SELECT * FROM skill_records ORDER BY created_at DESC",
            new SkillRecordRowMapper()
        );
    }

    /** 删除一条记录 */
    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM skill_records WHERE id = ?", id);
    }

    private static class SkillRecordRowMapper implements RowMapper<SkillRecordEntity> {
        @Override
        public SkillRecordEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SkillRecordEntity(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("package_name"),
                rs.getString("status"),
                rs.getString("created_at")
            );
        }
    }
}
