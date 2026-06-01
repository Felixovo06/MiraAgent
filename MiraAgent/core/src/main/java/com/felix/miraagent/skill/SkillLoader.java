package com.felix.miraagent.skill;

import java.util.List;
import java.util.Optional;

/**
 * Skill 读取门面（渐进式披露）：默认只给索引；完整 skill 与资源按需加载。
 * 组合 SkillIndexRepository（索引）与 SkillStore（文件正文）。
 */
public interface SkillLoader {

    /** 加载全部 Active skill 索引（注入 prompt 用，不含正文）。 */
    List<SkillIndex> loadActiveIndex();

    /** 按需加载完整 skill。 */
    Optional<Skill> loadSkill(String skillId);

    /** 仅加载 SKILL.md 正文。 */
    Optional<SkillContent> loadContent(String skillId);

    /** 加载 references/ 或 examples/ 下的某个资源文件。 */
    String loadResource(String skillId, String relativePath);

    /** 列出 skill 某子目录（references/examples）的文件名。 */
    List<String> listResources(String skillId, String subDir);
}
