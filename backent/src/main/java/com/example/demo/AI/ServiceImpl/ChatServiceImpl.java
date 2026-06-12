package com.example.demo.AI.ServiceImpl;

import com.example.demo.AI.ServiceImpl.Service.ChatService;
import com.example.demo.AI.Tools.GetUserLocationTool;
import com.example.demo.AI.util.SummarizingChatMemoryRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ChatServiceImpl implements ChatService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ChatModel chatModel;

    public ChatServiceImpl(ChatClient.Builder chatClientBuilder, VectorStore vectorStore, ChatModel chatModel, @Qualifier("myCustomTools") List<Object> toolBeans) {
        //创建底层的真实存储（比如基于内存的）
        ChatMemoryRepository baseRepository = new InMemoryChatMemoryRepository();
        //用你的自定义 Repository 包装它
        ChatMemoryRepository summarizingRepo = new SummarizingChatMemoryRepository(
                baseRepository, 10
        );
        //创建 ChatMemory
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(summarizingRepo) // 注入自定义仓库
                .maxMessages(20)
                .build();

//        var qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
//                .searchRequest(SearchRequest.builder().similarityThreshold(0.8d).topK(6).build())
//                .build();

        Advisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .similarityThreshold(0.50)
                        .vectorStore(vectorStore)
                        .build())
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .allowEmptyContext(true)
                        .build())
                .build();

        var memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();

        List<ToolCallbackProvider> providers = toolBeans.stream()
                        .map(bean -> MethodToolCallbackProvider.builder()
                                        .toolObjects(bean)
                                        .build())
                        .collect(Collectors.toList());

        SimpleLoggerAdvisor customLogger = new SimpleLoggerAdvisor(
                request -> "Custom request: " + request.prompt().getUserMessage(),
                response -> "Custom response: " + response.getResult(),
                0
        );
        this.chatClient = chatClientBuilder
                .defaultSystem("你是一个对话智能体，你必须用中文回答，除非用户强制你用英文")
                .defaultAdvisors(retrievalAugmentationAdvisor, memoryAdvisor, customLogger)
                .defaultToolCallbacks(providers.toArray(new ToolCallbackProvider[0]))
                .build();
        this.vectorStore = vectorStore;
        this.chatModel = chatModel;
    }

    public ChatClient.StreamResponseSpec getStreamResponseSpec(String chatId, String userInput) {
        return this.chatClient.prompt()
                .options(DeepSeekChatOptions.builder().build())
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, chatId))
                .toolContext(Map.of("chatId", chatId))
                .user(userInput)
                .stream();
    }

    public Flux<String> generation(String chatId, String userInput) {
         return this.getStreamResponseSpec(chatId, userInput).content();
    }
}
