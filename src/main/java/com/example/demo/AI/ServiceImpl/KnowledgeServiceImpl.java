package com.example.demo.AI.ServiceImpl;

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
public class KnowledgeServiceImpl implements com.example.demo.AI.ServiceImpl.Service.KnowledgeService {

    private final VectorStore vectorStore;

    private final TokenTextSplitter tokenTextSplitter;

    @Override
    public void AddKnowledge(MultipartFile file) {
        try{
            Resource resource = file.getResource();
            TikaDocumentReader reader = new TikaDocumentReader(resource);
            List<Document> documents = reader.read();

            List<Document> chunks = tokenTextSplitter.apply(documents);
            vectorStore.add(chunks);
            log.info("Added knowledge: {}", file.getName());
        }catch (Exception e){
            log.error("Error adding knowledge: {}", e.getMessage());
        }
    }

}
