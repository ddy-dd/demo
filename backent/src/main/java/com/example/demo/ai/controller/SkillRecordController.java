package com.example.demo.ai.controller;

import com.example.demo.ai.db.SkillRecordDao;
import com.example.demo.ai.db.model.SkillRecordEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Skill 记录 REST 控制器
 * <p>
 * 提供上传 Skill 元数据的增删查接口。
 * </p>
 */
@RestController
@Slf4j
@RequestMapping("/skill-records")
public class SkillRecordController {

    private final SkillRecordDao skillRecordDao;

    public SkillRecordController(SkillRecordDao skillRecordDao) {
        this.skillRecordDao = skillRecordDao;
    }

    /**
     * 获取所有 Skill 上传记录
     */
    @GetMapping
    public List<SkillRecordEntity> list() {
        return skillRecordDao.listAll();
    }

    /**
     * 删除一条 Skill 记录
     */
    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id) {
        skillRecordDao.delete(id);
        log.info("删除 Skill 记录: id={}", id);
        return Map.of("result", "ok");
    }
}
