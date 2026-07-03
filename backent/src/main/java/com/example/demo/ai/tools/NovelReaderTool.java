package com.example.demo.ai.tools;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 小说文件读取工具
 * <p>
 *  AI 调用此工具读取上传的小说文件内容，用于分析后生成结构化 Markdown。
 *  支持 .txt、.docx、.pdf 等格式（通过 Apache Tika 自动识别）。
 * </p>
 */
@Slf4j
@Component
public class NovelReaderTool {

    private final Tika tika = new Tika();

    @Tool(description = "读取上传的小说文件内容（支持 txt、docx、pdf 格式），返回纯文本。如需分页读取请指定 startLine 和 maxLines")
    public String readNovelFile(String filePath, Integer startLine, Integer maxLines) {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            return "文件不存在: " + filePath;
        }
        if (!Files.isReadable(path)) {
            return "文件不可读: " + filePath;
        }
        try {
            String name = path.getFileName().toString().toLowerCase();
            String text;
            if (name.endsWith(".txt")) {
                text = Files.readString(path);
            } else {
                // docx、pdf 等用 Tika 解析
                text = tika.parseToString(new File(filePath));
            }

            // 分页处理
            String[] lines = text.split("\n", -1);
            int s = (startLine != null && startLine > 0) ? startLine : 0;
            int m = (maxLines != null && maxLines > 0) ? maxLines : lines.length;
            int end = Math.min(s + m, lines.length);

            StringBuilder sb = new StringBuilder();
            sb.append("文件: ").append(path.getFileName()).append("\n");
            sb.append("总行数: ").append(lines.length).append("\n");
            sb.append("当前返回: 第 ").append(s + 1).append(" 行 ~ 第 ").append(end).append(" 行\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            for (int i = s; i < end; i++) {
                sb.append(lines[i]).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("读取文件失败: {}", filePath, e);
            return "读取文件失败: " + e.getMessage();
        }
    }
}
