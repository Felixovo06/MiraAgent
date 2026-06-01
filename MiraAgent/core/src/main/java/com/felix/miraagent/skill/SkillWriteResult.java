package com.felix.miraagent.skill;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SkillWriteResult {
    String skillId;
    String sourceUri;   // 相对 baseDir 的 SKILL.md 路径
    boolean success;
    String error;
}
