package com.example.demo.ai.chat.controller;

import com.example.demo.ai.chat.dao.ConversationDao;
import com.example.demo.ai.chat.model.ConversationEntity;
import com.example.demo.ai.chat.model.ChatMessageEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 对话 REST 控制器
 * <p>
 * 提供对话记录和消息的增删改查接口。
 * </p>
 */
@RestController
@Slf4j
@RequestMapping("/conversations")
public class ConversationController {

    private final ConversationDao conversationDao;

    public ConversationController(ConversationDao conversationDao) {
        this.conversationDao = conversationDao;
    }

    /**
     * 获取所有对话列表
     */
    @GetMapping
    public List<ConversationEntity> listConversations() {
        return conversationDao.listConversations();
    }

    /**
     * 获取单条对话详情
     */
    @GetMapping("/{id}")
    public ConversationEntity getConversation(@PathVariable String id) {
        ConversationEntity conv = conversationDao.getConversation(id);
        if (conv == null) {
            throw new IllegalArgumentException("对话不存在: " + id);
        }
        return conv;
    }

    /**
     * 创建新对话
     */
    @PostMapping
    public Map<String, String> createConversation(@RequestBody Map<String, String> body) {
        String id = body.get("id");
        String title = body.getOrDefault("title", "");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("对话 ID 不能为空");
        }
        conversationDao.createConversation(id, title);
        log.info("创建对话: id={}, title={}", id, title);
        return Map.of("id", id);
    }

    /**
     * 更新对话标题
     */
    @PutMapping("/{id}")
    public Map<String, String> updateConversation(@PathVariable String id,
                                                   @RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "");
        conversationDao.updateConversationTitle(id, title);
        return Map.of("id", id);
    }

    /**
     * 删除对话（级联删除消息）
     */
    @DeleteMapping("/{id}")
    public Map<String, String> deleteConversation(@PathVariable String id) {
        conversationDao.deleteConversation(id);
        log.info("删除对话: id={}", id);
        return Map.of("result", "ok");
    }

    /**
     * 获取某个对话的所有消息
     */
    @GetMapping("/{id}/messages")
    public List<ChatMessageEntity> getMessages(@PathVariable String id) {
        return conversationDao.getMessages(id);
    }

    /**
     * 向对话中添加消息
     */
    @PostMapping("/{id}/messages")
    public Map<String, String> addMessage(@PathVariable String id,
                                           @RequestBody ChatMessageEntity message) {
        if (!conversationDao.conversationExists(id)) {
            conversationDao.createConversation(id, "");
        }
        message.setConversationId(id);
        conversationDao.insertMessage(message);
        return Map.of("result", "ok");
    }
}
