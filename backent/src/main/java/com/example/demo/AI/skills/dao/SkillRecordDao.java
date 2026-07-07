package com.example.demo.ai.skills.dao;

import com.example.demo.ai.skills.model.SkillRecordEntity;
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
            "INSERT INTO skill_records (id, name, package_name, description, raw_content, is_system, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            record.getId(), record.getName(), record.getPackageName(),
            record.getDescription(), record.getRawContent(), record.getIsSystem(),
            record.getStatus(), record.getCreatedAt()
        );
    }

    /** 更新已有记录的 description 和 raw_content（用于重新解析） */
    public void updateContent(String id, String description, String rawContent) {
        jdbcTemplate.update(
            "UPDATE skill_records SET description = ?, raw_content = ? WHERE id = ?",
            description, rawContent, id
        );
    }

    /** 查询所有记录，按创建时间倒序 */
    public List<SkillRecordEntity> listAll() {
        return jdbcTemplate.query(
            "SELECT * FROM skill_records ORDER BY created_at DESC",
            new SkillRecordRowMapper()
        );
    }

    /** 查询所有记录，按系统内置→用户上传排序（用于注册表加载） */
    public List<SkillRecordEntity> findAllForRegistry() {
        return jdbcTemplate.query(
            "SELECT * FROM skill_records ORDER BY is_system DESC, created_at ASC",
            new SkillRecordRowMapper()
        );
    }

    /** 按 package_name 查询（用于去重检测） */
    public List<SkillRecordEntity> findByPackageName(String packageName) {
        return jdbcTemplate.query(
            "SELECT * FROM skill_records WHERE package_name = ? ORDER BY created_at DESC",
            new SkillRecordRowMapper(), packageName
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
                rs.getString("description"),
                rs.getString("raw_content"),
                rs.getInt("is_system"),
                rs.getString("status"),
                rs.getString("created_at")
            );
        }
    }
}
