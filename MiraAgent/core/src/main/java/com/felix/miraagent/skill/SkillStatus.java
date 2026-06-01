package com.felix.miraagent.skill;

/**
 * Skill 生命周期（v1 简化为两态，见 docs/07 §16 决策 4）。
 * 修补 skill 仍是 ACTIVE，不单独设 Improved/Consolidated 态。
 */
public enum SkillStatus {
    ACTIVE,
    ARCHIVED
}
