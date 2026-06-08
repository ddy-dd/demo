package com.example.demo.AI.ServiceImpl;

import com.example.demo.AI.ServiceImpl.Service.ChatService;
import com.example.demo.AI.util.SummarizingChatMemoryRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;

import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ChatServiceImpl implements ChatService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ChatModel chatModel;

    public ChatServiceImpl(ChatClient.Builder chatClientBuilder, VectorStore vectorStore, ChatModel chatModel) {
        //创建底层的真实存储（比如基于内存的）
        ChatMemoryRepository baseRepository = new InMemoryChatMemoryRepository();
        //用你的自定义 Repository 包装它
        ChatMemoryRepository summarizingRepo = new SummarizingChatMemoryRepository(
                baseRepository, 10, chatModel
        );
        //创建 ChatMemory
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(summarizingRepo) // 注入自定义仓库
                .maxMessages(20)
                .build();

        var qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder().similarityThreshold(0.8d).topK(6).build())
                .build();

        var memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();

        SimpleLoggerAdvisor customLogger = new SimpleLoggerAdvisor(
                request -> "Custom request: " + request.prompt().getUserMessage(),
                response -> "Custom response: " + response.getResult(),
                0
        );
        this.chatClient = chatClientBuilder.defaultSystem("你必须用中文回答，除非用户强制你用英文").defaultAdvisors(qaAdvisor, memoryAdvisor, customLogger).build();
        this.vectorStore = vectorStore;
        this.chatModel = chatModel;
    }

    public Flux<String> generation(String chatId, String userInput) {

        return this.chatClient.prompt()
                .options(OllamaChatOptions.builder().disableThinking().build())
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, chatId))
                .user(userInput)
                .stream()
                .content();
//        return "null";
    }
}
