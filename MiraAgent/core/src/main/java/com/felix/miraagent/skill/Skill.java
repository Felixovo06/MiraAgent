package com.felix.miraagent.skill;

import lombok.Builder;
import lombok.Value;

/**
 * Skill 聚合：元数据 + 正文。
 * content 可为 null —— 渐进式披露下只加载索引时正文延迟加载。
 */
@Value
@Builder
public class Skill {
    SkillMetadata metadata;
    SkillContent content;   // nullable

    public String getSkillId() {
        return metadata != null ? metadata.getSkillId() : null;
    }
}
