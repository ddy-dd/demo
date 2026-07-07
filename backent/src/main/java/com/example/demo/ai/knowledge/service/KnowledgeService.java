package com.example.demo.ai.knowledge.service;

import org.springframework.web.multipart.MultipartFile;


public interface KnowledgeService {
    void AddKnowledge(MultipartFile file);
}
