package com.felix.miraagent.skill;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;

/**
 * 一条 skill 使用/变更事件，追加进 history.jsonl，并驱动 metadata.json 计数。
 * 带来源引用（trace/session），与"写入必须记录来源"约束一致。
 */
@Value
@Builder
@Jacksonized
public class SkillUsageEvent {
    SkillUsageEventType type;
    String skillId;
    Instant at;
    String sourceTraceId;    // nullable
    String sourceSessionId;  // nullable
    String note;             // nullable，简短备注（如 patch 摘要）

    public static SkillUsageEvent of(SkillUsageEventType type, String skillId, Instant at) {
        return SkillUsageEvent.builder().type(type).skillId(skillId).at(at).build();
    }
}
