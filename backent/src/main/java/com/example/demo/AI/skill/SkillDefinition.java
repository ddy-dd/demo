package com.example.demo.ai.skill;

import org.yaml.snakeyaml.Yaml;

import java.util.Map;

/**
 * 从 SKILL.md 解析出的技能定义。
 *
 * 使用 SnakeYAML 解析 frontmatter，支持 Claude Code 定义的所有字段。
 */
public class SkillDefinition {

    /** 技能名称（唯一标识） */
    private final String name;

    /** 简短描述 */
    private final String description;

    /** 何时使用此技能 */
    private final String whenToUse;

    /** 原始 markdown 内容（含 frontmatter） */
    private final String rawContent;

    /** 不含 frontmatter 的纯正文 */
    private final String body;

    /** 完整的 frontmatter 数据（所有字段） */
    private final Map<String, Object> frontmatter;

    /** 内置 YAML 解析器 */
    private static final Yaml YAML = new Yaml();

    public SkillDefinition(String name, String description, String whenToUse,
                           String rawContent, String body) {
        this(name, description, whenToUse, rawContent, body, Map.of());
    }

    public SkillDefinition(String name, String description, String whenToUse,
                           String rawContent, String body, Map<String, Object> frontmatter) {
        this.name = name;
        this.description = description;
        this.whenToUse = whenToUse;
        this.rawContent = rawContent;
        this.body = body;
        this.frontmatter = frontmatter;
    }

    public String name() { return name; }
    public String description() { return description; }
    public String whenToUse() { return whenToUse; }
    public String rawContent() { return rawContent; }
    public String body() { return body; }
    public Map<String, Object> frontmatter() { return frontmatter; }

    /**
     * 从原始文本解析 SKILL.md，返回 SkillDefinition 和完整 frontmatter。
     */
    public static SkillDefinition parse(String content, String fallbackName) {
        String rawContent = content;
        String body = content;
        Map<String, Object> fm = Map.of();

        // 提取 --- ... --- frontmatter
        int start = content.indexOf("---");
        if (start == 0) {
            int end = content.indexOf("---", 3);
            if (end != -1) {
                String yamlBlock = content.substring(3, end).strip();
                body = content.substring(end + 3).strip();
                try {
                    Object parsed = YAML.load(yamlBlock);
                    if (parsed instanceof Map<?, ?> map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> casted = (Map<String, Object>) map;
                        fm = casted;
                    }
                } catch (Exception ignored) {
                    // YAML 解析失败，用 fallback
                }
            }
        }

        String name = fallbackName;
        if (fm.get("name") instanceof String s) name = s;

        String description = "";
        if (fm.get("description") instanceof String s) description = s;

        String whenToUse = "";
        // whenToUse 或 when_to_use 都支持
        if (fm.get("whenToUse") instanceof String s) whenToUse = s;
        else if (fm.get("when_to_use") instanceof String s) whenToUse = s;

        return new SkillDefinition(name, description, whenToUse, rawContent, body, fm);
    }
}
