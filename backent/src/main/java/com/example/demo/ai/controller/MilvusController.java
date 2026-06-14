package com.example.demo.ai.controller;

import com.example.demo.ai.serviceImpl.service.KnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;


@RestController
@Slf4j
@RequestMapping("/milvus")
@RequiredArgsConstructor
public class MilvusController {

    private final KnowledgeService knowledgeService;

    /**
     * 上传文件并存入知识库（向量化）
     * <p>
     * 支持格式：pdf, docx, txt, md 等（由 Apache Tika 自动识别）。
     * 文件内容会被分块、向量化后存入 Milvus，后续对话可通过 RAG 召回。
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

        // 业务逻辑由 KnowledgeService 执行，异常由 GlobalExceptionHandler 统一处理
        knowledgeService.AddKnowledge(file);
        log.info("文件上传成功: {}", file.getName());
        return "上传成功";
    }

}
