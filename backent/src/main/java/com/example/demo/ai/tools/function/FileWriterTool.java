package com.example.demo.ai.tools.function;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文件写入工具
 * <p>
 * AI 将生成的 .md 文件内容写入磁盘，
 * 配合 importDocument 工具完成知识图谱导入。
 * </p>
 */
@Slf4j
@Component
public class FileWriterTool {

    /** novelbase 导入目录根路径 */
    private static final Path NOVELBASE_DIR = Path.of(
        System.getProperty("user.home"),
        "project/novelbase-memory-mcp/novelbase"
    );

    @Tool(description = "将结构化 Markdown 内容保存到 novelbase/ 目录下的文件中，供 importDocument 导入。fileName 是文件名如 世界观.md，content 是文件内容，subdir 是子目录名可选")
    public String saveMarkdownFile(String fileName, String content, String subdir) {
        try {
            Path dir = subdir != null && !subdir.isBlank()
                ? NOVELBASE_DIR.resolve(subdir)
                : NOVELBASE_DIR;
            Files.createDirectories(dir);

            // 文件名防重
            Path filePath = dir.resolve(fileName);
            if (Files.exists(filePath)) {
                fileName = fileName.replace(".md", "") + "-" + System.currentTimeMillis() + ".md";
                filePath = dir.resolve(fileName);
            }

            Files.writeString(filePath, content);
            String absPath = filePath.toAbsolutePath().normalize().toString();
            log.info("已保存文件: {}", absPath);
            return "已保存: " + absPath;
        } catch (Exception e) {
            log.error("保存文件失败", e);
            return "保存失败: " + e.getMessage();
        }
    }

    @Tool(description = "将多个 .md 文件批量保存到 novelbase/ 目录下。files 传 JSON 数组，每个元素包含 fileName 和 content")
    public String saveMarkdownFiles(String files, String subdir) {
        try {
            // files 格式: fileName1|content1||fileName2|content2
            String[] entries = files.split("\\|\\|");
            Path dir = subdir != null && !subdir.isBlank()
                ? NOVELBASE_DIR.resolve(subdir)
                : NOVELBASE_DIR;
            Files.createDirectories(dir);

            List<String> saved = new java.util.ArrayList<>();
            for (String entry : entries) {
                int sep = entry.indexOf('|');
                if (sep <= 0) continue;
                String fn = entry.substring(0, sep);
                String content = entry.substring(sep + 1);
                Path fp = dir.resolve(fn);
                if (Files.exists(fp)) {
                    fn = fn.replace(".md", "") + "-" + System.currentTimeMillis() + ".md";
                    fp = dir.resolve(fn);
                }
                Files.writeString(fp, content);
                saved.add(fp.toAbsolutePath().normalize().toString());
            }
            return "已保存 " + saved.size() + " 个文件:\n" + String.join("\n", saved);
        } catch (Exception e) {
            log.error("批量保存文件失败", e);
            return "保存失败: " + e.getMessage();
        }
    }
}
