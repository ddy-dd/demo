package com.example.demo.ai.controller;

import com.example.demo.ai.db.SkillRecordDao;
import com.example.demo.ai.db.model.SkillRecordEntity;
import com.example.demo.ai.skill.SkillRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@RestController
@Slf4j
@RequestMapping("/skill")
public class SkillController {

    private final SkillRecordDao skillRecordDao;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path uploadDir;

    private final SkillRegistry skillRegistry;

    public SkillController(SkillRegistry skillRegistry,
                           @Value("${app.skills-dir:skills}") String skillsDir,
                           SkillRecordDao skillRecordDao) {
        this.skillRecordDao = skillRecordDao;
        this.skillRegistry = skillRegistry;
        Path base = resolveBaseDir(skillsDir);
        this.uploadDir = base.resolve("upload");
    }

    /**
     * 解析技能根目录（与 SkillRegistry 逻辑一致）
     * - 配置了非默认值 → 直接使用
     * - 否则从 user.dir 往上找 pom.xml 或 skills/ 目录
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

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         @RequestParam("packageName") String packageName) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".md")) {
            throw new IllegalArgumentException("仅支持 .md 文件");
        }
        if (packageName == null || packageName.isBlank()) {
            throw new IllegalArgumentException("请填写包名");
        }

        // 包名只允许小写英文、数字、连字符
        if (!packageName.matches("[a-z0-9][-a-z0-9]*")) {
            throw new IllegalArgumentException("包名格式错误，请使用小写英文+连字符，例如 my-tool");
        }

        // 检测重复包名，自动加 -1，-2 ...（最多 10 次）
        Path skillDir = deduplicatePath(uploadDir.resolve(packageName));
        if (skillDir == null) {
            throw new RuntimeException("上传失败: 包名重复过多（已达上限）");
        }
        String now = LocalDateTime.now().format(DTF);
        log.info("保存技能到: {}", skillDir.toAbsolutePath().normalize());
        try {
            Files.createDirectories(skillDir);
            Path target = skillDir.resolve("SKILL.md");
            file.transferTo(target.toFile());

            // 重新加载技能注册表
            skillRegistry.loadAll();
            log.info("技能上传成功: {}", packageName);

            // 保存记录到 SQLite
            SkillRecordEntity record = new SkillRecordEntity(
                UUID.randomUUID().toString(),
                filename,
                packageName,
                "success",
                now
            );
            skillRecordDao.insert(record);
        } catch (IOException e) {
            // 失败也记录
            SkillRecordEntity record = new SkillRecordEntity(
                UUID.randomUUID().toString(),
                filename,
                packageName,
                "error",
                now
            );
            skillRecordDao.insert(record);
            throw new RuntimeException("技能保存失败: " + e.getMessage());
        }
        return "上传成功";
    }

    /**
     * 检测路径冲突，自动追加 -1, -2 ... -9，最多尝试 10 次。
     *
     * @param original 原始路径
     * @return 可用的不重复路径，全部冲突则返回 null
     */
    private static Path deduplicatePath(Path original) {
        if (!Files.exists(original)) {
            return original;
        }
        Path parent = original.getParent();
        String name = original.getFileName().toString();
        for (int i = 1; i <= 9; i++) {
            Path candidate = parent.resolve(name + "-" + i);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
