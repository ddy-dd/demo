package com.example.demo.ai.skill;

import java.util.List;
import java.util.Map;

/**
 * 技能清单格式化工具 —— 按预算控制上下文占用。
 *
 * 逻辑移植自 Claude Code 的 formatCommandsWithinBudget：
 * 1. 预算默认 8000 字符（上下文窗口 1% × 4 chars/token）
 * 2. 每条描述硬上限 250 字符
 * 3. 先尝试完整格式，超了逐级截断
 * 4. 描述不足 20 字时只显示技能名
 */
public class SkillFormatter {

    /** 预算默认值：1% of 200k × 4 */
    static final int DEFAULT_CHAR_BUDGET = 8_000;

    /** 每条描述硬上限 */
    static final int MAX_DESC_CHARS = 250;

    /** 描述最小长度，低于此值则只显示名称 */
    static final int MIN_DESC_LENGTH = 20;

    /** 名称前缀固定开销："- " + ": " = 4 */
    static final int NAME_OVERHEAD = 4;

    /**
     * 将技能列表格式化为带预算控制的文本。
     *
     * @param skills 技能列表，每个元素是 [name, description]
     * @param bundledNames 内置技能名（完整显示，不截断）
     * @param budget 字符预算，0 则用默认值
     */
    public static String format(Map<String, String> skills, List<String> bundledNames, int budget) {
        if (skills.isEmpty()) return "";
        if (budget <= 0) budget = DEFAULT_CHAR_BUDGET;

        var entries = skills.entrySet().toArray(Map.Entry<?, ?>[]::new);
        int count = entries.length;

        // ── 第一轮：尝试完整格式 ──
        String[] fullLines = new String[count];
        int totalChars = 0;
        for (int i = 0; i < count; i++) {
            fullLines[i] = formatLine((String) entries[i].getKey(), (String) entries[i].getValue(), MAX_DESC_CHARS);
            totalChars += fullLines[i].length() + 1; // +1 for newline
        }

        if (totalChars <= budget) {
            return String.join("\n", fullLines);
        }

        // ── 第二轮：内置技能保留完整，其它截断 ──
        boolean[] isBundled = new boolean[count];
        int bundledChars = 0;
        int restCount = 0;
        for (int i = 0; i < count; i++) {
            if (bundledNames.contains(entries[i].getKey())) {
                isBundled[i] = true;
                bundledChars += fullLines[i].length() + 1;
            } else {
                restCount++;
            }
        }

        int remainingBudget = budget - bundledChars;
        if (restCount == 0) {
            return String.join("\n", fullLines);
        }

        // 非内置技能的名称开销（"- name\n"）
        int restNameOverhead = 0;
        for (int i = 0; i < count; i++) {
            if (!isBundled[i]) {
                restNameOverhead += ((String) entries[i].getKey()).length() + NAME_OVERHEAD;
            }
        }
        restNameOverhead += restCount - 1; // newlines

        int availableForDescs = remainingBudget - restNameOverhead;
        int maxDescLen = Math.floorDiv(availableForDescs, restCount);

        if (maxDescLen < MIN_DESC_LENGTH) {
            // 极端情况：非内置只显示名称
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) {
                if (isBundled[i]) {
                    sb.append(fullLines[i]);
                } else {
                    sb.append("- ").append(entries[i].getKey());
                }
                sb.append("\n");
            }
            return sb.toString().stripTrailing();
        }

        // 正常截断：按剩余预算裁剪描述
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (isBundled[i]) {
                sb.append(fullLines[i]);
            } else {
                sb.append(formatLine((String) entries[i].getKey(), (String) entries[i].getValue(), maxDescLen));
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /** 格式化一行：- name: description（description 截断到 maxLen） */
    private static String formatLine(String name, String description, int maxDescLen) {
        String desc = description;
        if (desc.length() > MAX_DESC_CHARS) {
            desc = desc.substring(0, MAX_DESC_CHARS - 1) + "…";
        }
        if (desc.length() > maxDescLen) {
            desc = desc.substring(0, Math.max(maxDescLen - 1, 0)) + "…";
        }
        return "- " + name + ": " + desc;
    }

    private SkillFormatter() {}
}
