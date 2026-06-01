package com.felix.miraagent.skill;

import java.util.Optional;

/**
 * Skill 使用统计：记录 view/use/patch 等事件，维护 metadata.json 计数与时间戳，并落 history.jsonl。
 * 是后续 curator（step7）判定未使用/过窄的依据。pinned skill 不会被自动归档。
 */
public interface SkillUsageTracker {

    /** 记录一条事件并返回更新后的 metadata（skill 不存在返回 empty）。 */
    Optional<SkillMetadata> record(SkillUsageEvent event);

    Optional<SkillMetadata> recordView(String skillId, String sourceTraceId, String sourceSessionId);

    Optional<SkillMetadata> recordUse(String skillId, String sourceTraceId, String sourceSessionId);

    Optional<SkillMetadata> recordPatch(String skillId, String sourceTraceId, String note);

    /** 设置/取消 pinned。 */
    Optional<SkillMetadata> setPinned(String skillId, boolean pinned);

    /** 是否允许（curator）自动归档：pinned 永不自动归档。 */
    default boolean canAutoArchive(SkillMetadata metadata) {
        return metadata != null && !metadata.isPinned() && metadata.getStatus() != SkillStatus.ARCHIVED;
    }
}
