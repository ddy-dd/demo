package com.example.demo.ai.knowledge.controller;

import com.example.demo.ai.knowledge.dao.KnowledgeFileDao;
import com.example.demo.ai.knowledge.model.KnowledgeFileEntity;
import com.example.demo.ai.knowledge.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequestMapping("/milvus")
@RequiredArgsConstructor
public class MilvusController {

    private final KnowledgeService knowledgeService;
    private final KnowledgeFileDao knowledgeFileDao;

    /** 知识库上传文件存放根目录 */
    private static final Path KNOWLEDGE_DIR = resolveKnowledgeDir();

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 解析知识库文件存放目录（从 user.dir 往上找 build.gradle → 项目根/knowledge）
     */
    private static Path resolveKnowledgeDir() {
        Path start = Path.of("").toAbsolutePath().normalize();
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve("build.gradle"))
                    || Files.exists(dir.resolve("settings.gradle"))
                    || Files.exists(dir.resolve("pom.xml"))) {
                return dir.resolve("knowledge");
            }
        }
        return start.resolve("knowledge");
    }

    /**
     * 上传文件并存入知识库（向量化）
     * <p>
     * 支持格式：pdf, docx, txt, md 等（由 Apache Tika 自动识别）。
     * 文件内容会被分块、向量化后存入 Milvus，后续对话可通过 RAG 召回。
     * 原始文件会同时保存到后端 knowledge/ 目录。
     * </p>
     *
     * @param file 上传的文件
     * @return 操作结果
     * @throws com.example.demo.ai.exception.AiServiceException 文件处理失败时抛出，由全局异常处理器统一返回错误信息
     */
    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }

        String now = LocalDateTime.now().format(DTF);
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : file.getName();
        String fileId = UUID.randomUUID().toString();
        String filePath = "";

        try {
            // 1. 保存原始文件到磁盘（跟 skills 一样）
            filePath = saveFileToDisk(file, fileId);
            log.info("文件已保存到磁盘: {}", filePath);

            // 2. 向量化存入 Milvus
            knowledgeService.AddKnowledge(file);
            log.info("文件向量化成功: {}", fileName);

            // 3. 保存记录到 SQLite
            KnowledgeFileEntity record = new KnowledgeFileEntity(
                fileId, fileName, file.getSize(), filePath, "success", now
            );
            knowledgeFileDao.insert(record);

            return "上传成功";
        } catch (Exception e) {
            // 上传失败也记录
            KnowledgeFileEntity record = new KnowledgeFileEntity(
                fileId, fileName, file.getSize(), filePath, "error", now
            );
            knowledgeFileDao.insert(record);
            throw new RuntimeException("文件处理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将上传文件保存到 knowledge/upload/ 目录下
     * @return 保存的绝对路径
     */
    private String saveFileToDisk(MultipartFile file, String fileId) throws IOException {
        Path uploadDir = KNOWLEDGE_DIR.resolve("upload");
        Files.createDirectories(uploadDir);
        // 用 fileId 重命名避免文件名冲突（保留扩展名）
        String originalName = file.getOriginalFilename();
        String ext = "";
        if (originalName != null && originalName.contains(".")) {
            ext = originalName.substring(originalName.lastIndexOf("."));
        }
        Path target = uploadDir.resolve(fileId + ext);
        file.transferTo(target.toFile());
        return target.toAbsolutePath().normalize().toString();
    }
}
