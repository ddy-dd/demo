package com.example.demo.ai.skills.service;

import com.example.demo.ai.skills.dao.SkillRecordDao;
import com.example.demo.ai.skills.model.SkillDefinition;
import com.example.demo.ai.skills.model.SkillRecordEntity;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 技能注册表 —— 从数据库加载技能
 *
 * 技能分两类，由 is_system 字段区分：
 * - 系统技能：首次启动从 skills/system/ 目录 seed 进数据库
 * - 用户技能：通过上传接口入库
 *
 * 不再依赖文件系统扫描（只在首次 seed 和上传时写文件供排查）。
 */
@Slf4j
@Component
@DependsOn("databaseInitializer")
public class SkillRegistry {

    private final SkillRecordDao skillRecordDao;

    /** 系统技能目录（仅用于首次 seed） */
    private final Path systemDir;

    /** name → SkillDefinition */
    private final Map<String, SkillDefinition> skills = new LinkedHashMap<>();

    /** 系统技能名称集合 */
    private final Set<String> systemNames = new HashSet<>();

    public SkillRegistry(SkillRecordDao skillRecordDao,
                         @Value("${app.skills-dir:skills}") String skillsDir) {
        this.skillRecordDao = skillRecordDao;
        Path base = resolveBaseDir(skillsDir);
        this.systemDir = base.resolve("system");
    }

    /**
     * 解析技能根目录：
     * 1. 配置了非默认值 → 直接使用
     * 2. 从 user.dir 往上找项目构建文件（build.gradle / pom.xml）→ 项目根 /skills
     * 3. 兜底：user.dir/skills
     */
    private static Path resolveBaseDir(String configured) {
        if (configured != null && !configured.isBlank() && !configured.equals("skills")) {
            return Path.of(configured);
        }
        Path start = Path.of("").toAbsolutePath().normalize();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve("build.gradle"))
                    || Files.exists(dir.resolve("settings.gradle"))
                    || Files.exists(dir.resolve("pom.xml"))) {
                return dir.resolve("skills");
            }
            Path skillsDir = dir.resolve("skills");
            if (Files.isDirectory(skillsDir.resolve("system"))) {
                return skillsDir;
            }
        }
        // 兜底：user.dir/skills
        return start.resolve("skills");
    }

    @PostConstruct
    public void loadAll() {
        skills.clear();
        systemNames.clear();

        // 首次启动：种子系统技能（磁盘 → 数据库，仅一次）
        seedSystemSkillsIfNeeded();

        // 从数据库加载所有技能
        List<SkillRecordEntity> records = skillRecordDao.findAllForRegistry();
        for (SkillRecordEntity record : records) {
            SkillDefinition def = toDefinition(record);
            if (def != null) {
                skills.put(def.name(), def);
                if (record.getIsSystem() == 1) {
                    systemNames.add(def.name());
                }
            }
        }

        log.info("已从数据库加载 {} 个技能（系统: {}, 用户: {}）",
                skills.size(), systemNames.size(), skills.size() - systemNames.size());
    }

    /**
     * 首次启动时，从 skills/system/ 目录读取并 seed 到数据库。
     * 仅在 DB 中没有任何系统技能时执行一次。
     */
    private void seedSystemSkillsIfNeeded() {
        List<SkillRecordEntity> existing = skillRecordDao.findAllForRegistry();
        boolean hasSystem = existing.stream().anyMatch(r -> r.getIsSystem() == 1);
        if (hasSystem) {
            return;
        }

        if (!Files.isDirectory(systemDir)) {
            log.warn("系统技能目录不存在，跳过 seed: {}", systemDir);
            return;
        }

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        try (var entries = Files.list(systemDir)) {
            entries.filter(Files::isDirectory).forEach(subDir -> {
                Path skillFile = subDir.resolve("SKILL.md");
                if (!Files.isRegularFile(skillFile)) return;
                try {
                    String rawContent = Files.readString(skillFile);
                    SkillDefinition def = SkillDefinition.parse(rawContent, subDir.getFileName().toString());
                    SkillRecordEntity record = new SkillRecordEntity(
                        UUID.randomUUID().toString(),
                        skillFile.getFileName().toString(),
                        subDir.getFileName().toString(),
                        def.description(),
                        rawContent,
                        1,  // is_system
                        "success",
                        now
                    );
                    skillRecordDao.insert(record);
                    log.info("Seed 系统技能: {} ← {}", def.name(), skillFile);
                } catch (IOException e) {
                    log.warn("读取系统技能失败: {}", skillFile, e);
                }
            });
        } catch (IOException e) {
            log.warn("扫描系统技能目录失败: {}", systemDir, e);
        }
    }

    /** 将数据库记录转为 SkillDefinition */
    private static SkillDefinition toDefinition(SkillRecordEntity record) {
        String rawContent = record.getRawContent();
        if (rawContent == null || rawContent.isBlank()) {
            return null;
        }
        return SkillDefinition.parse(rawContent, record.getPackageName());
    }

    /** 所有技能清单（name → description） */
    public Map<String, String> listAll() {
        Map<String, String> result = new LinkedHashMap<>();
        skills.forEach((name, def) -> result.put(name, def.description()));
        return result;
    }

    /** 是否为系统自带技能 */
    public boolean isSystem(String name) {
        return systemNames.contains(name);
    }

    /** 系统技能名称列表 */
    public List<String> systemSkillNames() {
        return List.copyOf(systemNames);
    }

    /** 按名称获取技能 */
    public Optional<SkillDefinition> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    /** 技能总数 */
    public int count() {
        return skills.size();
    }
}
