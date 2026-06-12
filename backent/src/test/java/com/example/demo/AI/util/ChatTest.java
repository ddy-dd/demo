package com.example.demo.AI.util;

import com.example.demo.AI.Pool.ToolAwaitingPoolByCompletableFuture;
import com.example.demo.AI.ServiceImpl.Service.ChatService;
import com.example.demo.AI.Tools.GetUserLocationTool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
public class ChatTest {

    @Autowired
    private ChatService chatService;

    @Mock
    private ToolAwaitingPoolByCompletableFuture toolAwaitingPool;

    // 将 Mock 对象自动注入到 Tool 的构造函数中
    @InjectMocks
    private GetUserLocationTool getUserLocationTool;
    @Test
    public void test() throws Exception {
        Map<String, Double> fakeLocation = Map.of(
                "latitude", 39.9042,
                "longitude", 116.4074
        );

        // 2. 【核心操作】当调用 waitForResult 时，直接返回假数据，不阻塞
        when(toolAwaitingPool.waitForResult(anyString())).thenReturn(fakeLocation.toString());
        int count = 0;
        StringBuilder thinking = new StringBuilder();
        List<String> thinkingList = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        List<String> contentList = new ArrayList<>();
        chatService.getStreamResponseSpec("1","我在哪")
                .chatResponse()
                .doOnNext(chatResponse -> {
                    DeepSeekAssistantMessage assistantMessage = (DeepSeekAssistantMessage) chatResponse.getResult().getOutput();
                    if(assistantMessage.getReasoningContent()!= null && !assistantMessage.getReasoningContent().isEmpty()){
                        thinkingList.add(assistantMessage.getReasoningContent());
                    }
                    if(assistantMessage.getText()!= null &&!assistantMessage.getText().isEmpty()){
                        contentList.add(assistantMessage.getText());
                    }
                    if(!assistantMessage.getToolCalls().isEmpty()){
                        System.out.println(assistantMessage.getToolCalls());
                    }
                    //System.out.println(assistantMessage.getToolCalls());
                })
                .blockLast();
        for (String s : thinkingList) {
            thinking.append(s);
        }
        for (String s : contentList) {
            content.append(s);
        }
        System.out.println("---------------------------");
        System.out.println(thinking);
        System.out.println("---------------------------");
        System.out.println(content);
    }
}
