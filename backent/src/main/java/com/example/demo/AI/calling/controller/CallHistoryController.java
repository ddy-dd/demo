package com.example.demo.ai.calling.controller;

import com.example.demo.ai.calling.dao.CallRecordDao;
import com.example.demo.ai.calling.model.CallMessageEntity;
import com.example.demo.ai.calling.model.CallRecordEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 通话历史 REST 控制器
 *
 * <p>提供通话记录的查询接口，供 CallingPage 挂载时加载历史消息。</p>
 */
@RestController
@Slf4j
@RequestMapping("/call-history")
public class CallHistoryController {

    private final CallRecordDao callRecordDao;

    public CallHistoryController(CallRecordDao callRecordDao) {
        this.callRecordDao = callRecordDao;
    }

    /**
     * 获取所有通话记录（按开始时间倒序）
     */
    @GetMapping
    public List<CallRecordEntity> listCalls() {
        return callRecordDao.listCalls();
    }

    /**
     * 获取指定通话的消息
     */
    @GetMapping("/{callId}/messages")
    public List<CallMessageEntity> getMessages(@PathVariable String callId) {
        return callRecordDao.getMessages(callId);
    }

    /**
     * 获取最近的活跃通话消息（若指定 callId 不存在则返回空）
     */
    @GetMapping("/latest")
    public Map<String, Object> getLatest() {
        List<CallRecordEntity> calls = callRecordDao.listCalls();
        if (calls.isEmpty()) {
            return Map.of("call", null, "messages", List.of());
        }
        CallRecordEntity latest = calls.get(0);
        List<CallMessageEntity> messages = callRecordDao.getMessages(latest.getId());
        return Map.of("call", latest, "messages", messages);
    }
}
