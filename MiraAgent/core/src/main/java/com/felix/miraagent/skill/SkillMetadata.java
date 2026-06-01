package com.felix.miraagent.skill;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;
import java.util.List;

/**
 * metadata.json 的领域模型：保存 skill 索引、状态、统计和来源。
 * 不保存任何用户隐私事实（隐私物理隔离，见 docs/07 §16）。
 */
@Value
@Builder(toBuilder = true)
@Jacksonized
public class SkillMetadata {
    String skillId;
    String name;
    String description;
    SkillStatus status;

    /** 创建来源：user / housekeeping / background_review。 */
    String source;
    String sourceTraceId;    // nullable
    String sourceSessionId;  // nullable

    int version;
    List<String> tags;
    boolean pinned;          // pinned skill 既不被自动归档也不被自动合并

    // 使用统计（step3 SkillUsageTracker 维护）
    int useCount;
    int viewCount;
    int patchCount;
    int successCount;
    int failureCount;

    Instant createdAt;
    Instant updatedAt;
    Instant lastUsedAt;      // nullable
    Instant lastViewedAt;    // nullable
    Instant lastPatchedAt;   // nullable
}
