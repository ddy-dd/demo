package com.example.demo.ai.controller;

import com.example.demo.ai.db.KnowledgeFileDao;
import com.example.demo.ai.db.model.KnowledgeFileEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 知识库文件记录 REST 控制器
 * <p>
 * 提供知识库上传文件元数据的增删查接口。
 * </p>
 */
@RestController
@Slf4j
@RequestMapping("/knowledge-files")
public class KnowledgeFileController {

    private final KnowledgeFileDao knowledgeFileDao;

    public KnowledgeFileController(KnowledgeFileDao knowledgeFileDao) {
        this.knowledgeFileDao = knowledgeFileDao;
    }

    /**
     * 获取所有知识库文件上传记录
     */
    @GetMapping
    public List<KnowledgeFileEntity> list() {
        return knowledgeFileDao.listAll();
    }

    /**
     * 删除一条知识库文件记录
     */
    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id) {
        knowledgeFileDao.delete(id);
        log.info("删除知识库文件记录: id={}", id);
        return Map.of("result", "ok");
    }
}
