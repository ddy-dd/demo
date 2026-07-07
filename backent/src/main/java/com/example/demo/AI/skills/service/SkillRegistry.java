package com.example.demo.ai.skills.service;

import com.example.demo.ai.skills.model.SkillDefinition;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 技能注册表 —— 扫描 skills/system/ 和 skills/upload/ 两个目录加载技能
 *
 * 技能分两类，由所在目录天然区分：
 * - 系统技能（skills/system/）：项目自带，清单中完整显示
 * - 用户技能（skills/upload/）：通过上传接口安装，按预算截断显示
 */
@Slf4j
@Component
public class SkillRegistry {

    /** 技能目录根路径 */
    private final Path skillsBase;

    /** 系统技能目录 */
    private final Path systemDir;

    /** 用户上传技能目录 */
    private final Path uploadDir;

    /** name → SkillDefinition */
    private final Map<String, SkillDefinition> skills = new LinkedHashMap<>();

    /** 系统技能名称集合 */
    private final Set<String> systemNames = new HashSet<>();

    public SkillRegistry(@Value("${app.skills-dir:skills}") String skillsDir) {
        Path base = resolveBaseDir(skillsDir);
        this.skillsBase = base;
        this.systemDir = this.skillsBase.resolve("system");
        this.uploadDir = this.skillsBase.resolve("upload");
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

        // 扫描系统技能目录
        loadFromDir(systemDir, true);
        // 扫描用户上传技能目录
        loadFromDir(uploadDir, false);

        log.info("技能目录: {}  (系统: {}, 上传: {})",
                skillsBase.toAbsolutePath().normalize(), systemDir, uploadDir);
        log.info("已加载 {} 个技能（系统: {}, 用户: {}）",
                skills.size(), systemNames.size(),
                skills.size() - systemNames.size());
    }

    /** 加载指定目录下的所有技能 */
    private void loadFromDir(Path dir, boolean isSystem) {
        if (!Files.isDirectory(dir)) {
            log.debug("技能目录不存在，跳过: {}", dir);
            return;
        }
        try (var entries = Files.list(dir)) {
            entries.filter(Files::isDirectory).forEach(subDir -> {
                SkillDefinition def = loadSkillFromDir(subDir);
                if (def != null && isSystem) {
                    systemNames.add(def.name());
                }
            });
        } catch (IOException e) {
            log.warn("读取技能目录失败: {}", dir, e);
        }
    }

    /** 加载单个技能目录下的 SKILL.md */
    private SkillDefinition loadSkillFromDir(Path skillDir) {
        Path skillFile = skillDir.resolve("SKILL.md");
        if (!Files.isRegularFile(skillFile)) return null;
        try {
            String content = Files.readString(skillFile);
            SkillDefinition def = SkillDefinition.parse(content, skillDir.getFileName().toString());
            skills.put(def.name(), def);
            log.debug("加载技能: {} ← {}", def.name(), skillFile);
            return def;
        } catch (IOException e) {
            log.warn("读取 {} 失败: {}", skillFile, e.getMessage());
            return null;
        }
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
