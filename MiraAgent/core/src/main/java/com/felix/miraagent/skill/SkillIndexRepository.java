package com.felix.miraagent.skill;

import java.util.List;
import java.util.Optional;

/**
 * Skill 索引表端口（镜像 MemoryIndexRepository）。skills 表用 MyBatis-Plus 实现；
 * 向量去重查询（cosine <=>）留给 step4 的自定义 SQL。
 */
public interface SkillIndexRepository {

    void save(SkillIndex index);

    void archive(String skillId);

    /** 全部 Active（未归档）skill 索引，用于 prompt 注入与 curator 扫描。 */
    List<SkillIndex> findActive();

    Optional<SkillIndex> findById(String skillId);

    /** 清空索引（全量重建时使用）。 */
    void deleteAll();
}
