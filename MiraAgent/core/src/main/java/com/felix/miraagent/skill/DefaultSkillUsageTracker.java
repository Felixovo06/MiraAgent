package com.felix.miraagent.skill;

import java.time.Instant;
import java.util.Optional;

/**
 * SkillUsageTracker 默认实现：基于 SkillStore 做 metadata 读-改-写 + history 追加。
 * 计数策略放在 core（可测）；文件 IO 委托 store。step4 起所有写入由单 writer 串行化，
 * 此处暂以 synchronized 保护读-改-写。
 */
public class DefaultSkillUsageTracker implements SkillUsageTracker {

    private final SkillStore skillStore;

    public DefaultSkillUsageTracker(SkillStore skillStore) {
        this.skillStore = skillStore;
    }

    @Override
    public synchronized Optional<SkillMetadata> record(SkillUsageEvent event) {
        if (event == null || event.getSkillId() == null) {
            return Optional.empty();
        }
        Optional<SkillMetadata> loaded = skillStore.loadMetadata(event.getSkillId());
        if (loaded.isEmpty()) {
            return Optional.empty();
        }
        Instant at = event.getAt() != null ? event.getAt() : Instant.now();
        SkillMetadata updated = apply(loaded.get(), event.getType(), at);
        skillStore.saveMetadata(updated);
        skillStore.appendHistory(event.getSkillId(), event);
        return Optional.of(updated);
    }

    private SkillMetadata apply(SkillMetadata m, SkillUsageEventType type, Instant at) {
        SkillMetadata.SkillMetadataBuilder b = m.toBuilder().updatedAt(at);
        switch (type) {
            case VIEWED -> b.viewCount(m.getViewCount() + 1).lastViewedAt(at);
            case USED -> b.useCount(m.getUseCount() + 1).lastUsedAt(at);
            case PATCHED -> b.patchCount(m.getPatchCount() + 1).lastPatchedAt(at)
                    .version(m.getVersion() + 1);
            case PINNED -> b.pinned(true);
            case UNPINNED -> b.pinned(false);
            case ARCHIVED -> b.status(SkillStatus.ARCHIVED);
            default -> { /* CREATED/UPDATED 不在此路径调整计数 */ }
        }
        return b.build();
    }

    @Override
    public Optional<SkillMetadata> recordView(String skillId, String sourceTraceId, String sourceSessionId) {
        return record(SkillUsageEvent.builder()
                .type(SkillUsageEventType.VIEWED).skillId(skillId).at(Instant.now())
                .sourceTraceId(sourceTraceId).sourceSessionId(sourceSessionId).build());
    }

    @Override
    public Optional<SkillMetadata> recordUse(String skillId, String sourceTraceId, String sourceSessionId) {
        return record(SkillUsageEvent.builder()
                .type(SkillUsageEventType.USED).skillId(skillId).at(Instant.now())
                .sourceTraceId(sourceTraceId).sourceSessionId(sourceSessionId).build());
    }

    @Override
    public Optional<SkillMetadata> recordPatch(String skillId, String sourceTraceId, String note) {
        return record(SkillUsageEvent.builder()
                .type(SkillUsageEventType.PATCHED).skillId(skillId).at(Instant.now())
                .sourceTraceId(sourceTraceId).note(note).build());
    }

    @Override
    public Optional<SkillMetadata> setPinned(String skillId, boolean pinned) {
        return record(SkillUsageEvent.builder()
                .type(pinned ? SkillUsageEventType.PINNED : SkillUsageEventType.UNPINNED)
                .skillId(skillId).at(Instant.now()).build());
    }
}
