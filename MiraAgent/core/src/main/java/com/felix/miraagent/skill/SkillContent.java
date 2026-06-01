package com.felix.miraagent.skill;

import lombok.Builder;
import lombok.Value;

/**
 * SKILL.md 本体（skill 正文）。带 YAML frontmatter（至少 name/description）+ markdown 正文。
 * 渐进式披露：完整内容只在需要时加载（见 docs/07 §9）。
 */
@Value
@Builder
public class SkillContent {
    String name;          // 来自 frontmatter
    String description;   // 来自 frontmatter
    String body;          // frontmatter 之后的 markdown 正文
    String raw;           // 完整 SKILL.md（含 frontmatter），文件事实源

    /**
     * 解析 SKILL.md 文本，抽出 frontmatter 的 name/description 与正文。
     * 容错：无 frontmatter 时 name/description 为 null，body=整篇。
     */
    public static SkillContent parse(String markdown) {
        if (markdown == null) {
            return SkillContent.builder().raw("").body("").build();
        }
        String normalized = markdown.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return SkillContent.builder().raw(markdown).body(normalized.trim()).build();
        }
        int end = normalized.indexOf("\n---", 4);
        if (end < 0) {
            return SkillContent.builder().raw(markdown).body(normalized.trim()).build();
        }
        String frontmatter = normalized.substring(4, end);
        int bodyStart = normalized.indexOf('\n', end + 1);
        String body = bodyStart >= 0 ? normalized.substring(bodyStart + 1).trim() : "";

        String name = null;
        String description = null;
        for (String line : frontmatter.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if ("name".equals(key)) {
                name = value;
            } else if ("description".equals(key)) {
                description = value;
            }
        }
        return SkillContent.builder()
                .name(name)
                .description(description)
                .body(body)
                .raw(markdown)
                .build();
    }

    /**
     * 用 name/description/body 组装完整 SKILL.md（含 frontmatter）。
     */
    public static SkillContent of(String name, String description, String body) {
        String safeBody = body != null ? body.trim() : "";
        String raw = "---\n"
                + "name: " + (name != null ? name : "") + "\n"
                + "description: " + (description != null ? description : "") + "\n"
                + "---\n\n"
                + safeBody + "\n";
        return SkillContent.builder()
                .name(name)
                .description(description)
                .body(safeBody)
                .raw(raw)
                .build();
    }
}
