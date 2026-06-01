package com.felix.miraagent.skill;

/**
 * Skill 生命周期/使用事件类型，落 history.jsonl。
 */
public enum SkillUsageEventType {
    CREATED,
    UPDATED,
    VIEWED,
    USED,
    PATCHED,
    ARCHIVED,
    PINNED,
    UNPINNED
}
