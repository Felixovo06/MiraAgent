package com.felix.miraagent.skill;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

/**
 * Skill 索引行（DB 事实源镜像）。注入 prompt 的渐进式披露索引即由此构成，
 * 也是 curator 统计/去重检索的查询对象。skill 是 Agent 全局能力，不挂 userId。
 */
@Value
@Builder
public class SkillIndex {
    String skillId;
    String name;
    String description;
    SkillStatus status;
    List<String> tags;
    boolean pinned;
    int useCount;
    int version;
    String sourceUri;        // e.g. "skills/<id>/SKILL.md"
    String sourceTraceId;    // nullable
    String sourceSessionId;  // nullable
    String embeddingRef;     // nullable，留给 step4 向量去重
    Instant lastUsedAt;      // nullable
    Instant archivedAt;      // nullable，逻辑删除
    Instant createdAt;
    Instant updatedAt;

    /** 无 DB 回退时由 metadata 直接构造索引。 */
    public static SkillIndex fromMetadata(SkillMetadata m) {
        return SkillIndex.builder()
                .skillId(m.getSkillId())
                .name(m.getName())
                .description(m.getDescription())
                .status(m.getStatus())
                .tags(m.getTags())
                .pinned(m.isPinned())
                .useCount(m.getUseCount())
                .version(m.getVersion())
                .sourceUri("skills/" + m.getSkillId() + "/SKILL.md")
                .sourceTraceId(m.getSourceTraceId())
                .sourceSessionId(m.getSourceSessionId())
                .lastUsedAt(m.getLastUsedAt())
                .archivedAt(m.getStatus() == SkillStatus.ARCHIVED ? m.getUpdatedAt() : null)
                .createdAt(m.getCreatedAt())
                .updatedAt(m.getUpdatedAt())
                .build();
    }
}
