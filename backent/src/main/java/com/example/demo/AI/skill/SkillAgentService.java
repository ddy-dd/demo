package com.example.demo.ai.skill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI Agent 可调用的技能工具。
 *
 * 注册两个 @Tool 供 AI 使用：
 * - listSkills() → 浏览所有可用技能（预算控制，自动截断）
 * - readSkill(name) → 加载某个技能的完整指令
 *
 * AI 通过这两个工具实现「渐进式发现」：
 * 1. 看用户请求 → 觉得可能用得上某技能
 * 2. 调用 listSkills() 看有哪些
 * 3. 调用 readSkill("weather") 读详情
 * 4. 根据详情执行操作
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillAgentService {

    private final SkillRegistry skillRegistry;

    /** 技能清单字符预算，默认 8000（1% of 200k × 4） */
    @Value("${app.skills.list-budget:8000}")
    private int listBudget;

    @Tool(name = "listSkills", description = "列出所有可用的技能及其说明")
    public String listSkills() {
        var all = skillRegistry.listAll();
        if (all.isEmpty()) {
            return "当前没有可用技能。";
        }

        String formatted = SkillFormatter.format(all, List.of(), listBudget);
        return "可用技能列表：\n" + formatted;
    }

    @Tool(name = "readSkill",
          description = "读取指定技能的完整指令内容。调用此工具后，技能中的说明将成为你后续行为的依据。")
    public String readSkill(String name) {
        return skillRegistry.get(name)
                .map(def -> {
                    log.info("AI 读取技能: {}", name);
                    return def.body();
                })
                .orElseGet(() -> {
                    log.warn("AI 尝试读取不存在的技能: {}", name);
                    return "未找到技能: " + name + "。请先调用 listSkills() 查看可用技能。";
                });
    }
}
