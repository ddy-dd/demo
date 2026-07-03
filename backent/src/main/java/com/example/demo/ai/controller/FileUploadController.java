package com.example.demo.ai.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * 文件上传控制器（用于 AI 分析小说）
 * <p>
 *  仅仅保存文件到磁盘，不做向量化，专供 AI 读取分析用。
 * </p>
 */
@RestController
@Slf4j
@RequestMapping("/file")
public class FileUploadController {

    private static final Path NOVEL_DIR = Path.of("").toAbsolutePath().normalize()
            .resolve("knowledge/novels");

    @PostMapping("/novel-upload")
    public Map<String, String> uploadNovel(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        try {
            Files.createDirectories(NOVEL_DIR);
            String fileId = UUID.randomUUID().toString();
            String originalName = file.getOriginalFilename();
            String ext = "";
            if (originalName != null && originalName.contains(".")) {
                ext = originalName.substring(originalName.lastIndexOf("."));
            }
            String fileName = fileId + ext;
            Path target = NOVEL_DIR.resolve(fileName);
            file.transferTo(target.toFile());

            String absolutePath = target.toAbsolutePath().normalize().toString();
            log.info("小说文件已保存: {}", absolutePath);

            return Map.of(
                "id", fileId,
                "path", absolutePath,
                "name", originalName != null ? originalName : file.getName()
            );
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败: " + e.getMessage());
        }
    }
}
