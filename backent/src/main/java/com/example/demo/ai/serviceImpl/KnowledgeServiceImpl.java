package com.example.demo.ai.serviceImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeServiceImpl implements com.example.demo.ai.serviceImpl.service.KnowledgeService {

    private final VectorStore vectorStore;

    private final TokenTextSplitter tokenTextSplitter;

        /**
     * 上传文件并向量化存储到 Milvus
     * <p>
     * 流程：读取文件 → Tika 解析文本 → TokenTextSplitter 分块 → 嵌入并存入向量库
     * 存储后可通过 RAG Advisor 在对话中检索召回。
     * </p>
     *
     * @param file 上传的文件（支持 pdf/docx/txt 等 Tika 可解析格式）
     * @throws com.example.demo.ai.exception.AiServiceException 当解析或向量化失败时抛出
     */
    @Override
    public void AddKnowledge(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空: " + file.getName());
        }
        try {
            // 1️⃣ 用 Apache Tika 解析文件内容（自动识别格式）
            Resource resource = file.getResource();
            TikaDocumentReader reader = new TikaDocumentReader(resource);
            List<Document> documents = reader.read();

            if (documents.isEmpty()) {
                log.warn("文件 [{}] 解析后无文本内容", file.getName());
                return;
            }

            // 2️⃣ 文本分块（按 Token 数切割，保留上下文重叠）
            List<Document> chunks = tokenTextSplitter.apply(documents);
            log.info("文件 [{}] 解析完成，共 {} 个分块", file.getName(), chunks.size());

            // 3️⃣ 向量化并存入 Milvus
            vectorStore.add(chunks);
            log.info("知识库添加成功: file={}, chunks={}", file.getName(), chunks.size());

        } catch (Exception e) {
            log.error("知识库添加失败: file={}, error={}", file.getName(), e.getMessage(), e);
            throw new com.example.demo.ai.exception.AiServiceException("文件处理失败: " + e.getMessage(), e);
        }
    }

}
