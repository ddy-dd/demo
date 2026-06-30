package com.example.demo.ai.config;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文本分块配置
 *
 * 知识库文件上传后，需要将长文本分割为适合向量检索的片段。
 * TokenTextSplitter 按 Token 数切割，并保留上下文重叠以维持语义连贯性。
 */
@Configuration
public class ChunkConfig {

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return TokenTextSplitter.builder().build();
    }
}
