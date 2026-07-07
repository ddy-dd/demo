package com.example.demo.ai.skills.service;

import com.example.demo.ai.skills.util.SkillFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AI Agent 可调用的技能工具。
 *
 * 注册 @Tool 供 AI 读取技能的完整指令。
 * AI 根据系统提示中的技能列表判断哪个适用，需要时调用 readSkill 获取正文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillAgentService {

    private final SkillRegistry skillRegistry;

    @Tool(name = "readSkill",
          description = "读取指定技能的完整指令内容。调用此 tool 后，技能中的说明将成为你后续行为的依据。")
    public String readSkill(String name) {
        return skillRegistry.get(name)
                .map(def -> {
                    log.info("AI 读取技能: {}", name);
                    return def.body();
                })
                .orElseGet(() -> {
                    log.warn("AI 尝试读取不存在的技能: {}", name);
                    return "未找到技能: " + name + "。可用技能在系统提示的「当前可用技能」列表中。";
                });
    }
}
