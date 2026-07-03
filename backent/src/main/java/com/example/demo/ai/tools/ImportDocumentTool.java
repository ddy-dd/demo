package com.example.demo.ai.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * 导入结构化 Markdown 到 novelbase 知识图谱
 * <p>
 * 调用 novelbase-memory-mcp 的 cli import 命令，
 * 将 AI 按 import-document skill 规范生成的 .md 文件导入知识图谱。
 * </p>
 */
@Slf4j
@Component
public class ImportDocumentTool {

    private final String binaryPath;

    public ImportDocumentTool(
            @Value("${app.novelbase.binary:}") String binaryPath) {
        this.binaryPath = resolveBinary(binaryPath);
        log.info("novelbase 导入工具就绪, 二进制: {}", this.binaryPath);
    }

    @Tool(description = "将结构化 Markdown 文件/目录导入 novelbase 知识图谱。path 是 .md 文件或包含 .md 文件的目录路径，project 是项目名")
    public String importDocument(String path, String project) {
        var command = new java.util.ArrayList<String>();
        command.add(binaryPath);
        command.add("cli");
        command.add("import");
        command.add(path);
        command.add("--project");
        command.add(project != null && !project.isBlank() ? project : "default");

        log.debug("执行: {}", String.join(" ", command));

        try {
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            // 设工作目录为 novelbase 项目根
            var binPath = Path.of(binaryPath).toAbsolutePath().normalize();
            for (int i = 0; i < 3; i++) {
                var parent = binPath.getParent();
                if (parent == null) break;
                binPath = parent;
            }
            if (Files.isDirectory(binPath)) {
                pb.directory(binPath.toFile());
            }

            var process = pb.start();
            String output;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                log.warn("novelbase import 返回非零: {} | 输出: {}", exitCode, output);
                return "导入失败: " + output;
            }
            return output;
        } catch (Exception e) {
            log.error("执行 novelbase import 异常", e);
            return "导入异常: " + e.getMessage();
        }
    }

    private static String resolveBinary(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        Path start = Path.of("").toAbsolutePath().normalize();
        Path candidate = start.resolve("../novelbase-memory-mcp/target/release/novelbase-memory-mcp").normalize();
        if (Files.exists(candidate)) return candidate.toString();
        candidate = start.resolve("../../novelbase-memory-mcp/target/release/novelbase-memory-mcp").normalize();
        if (Files.exists(candidate)) return candidate.toString();
        candidate = Path.of("/Users/xiaozhang/project/novelbase-memory-mcp/target/release/novelbase-memory-mcp");
        if (Files.exists(candidate)) return candidate.toString();
        return "../novelbase-memory-mcp/target/release/novelbase-memory-mcp";
    }
}
