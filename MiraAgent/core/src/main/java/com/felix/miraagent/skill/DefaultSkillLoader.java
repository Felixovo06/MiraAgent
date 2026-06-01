package com.felix.miraagent.skill;

import java.util.List;
import java.util.Optional;

/**
 * SkillLoader 默认实现：索引优先取自 SkillIndexRepository（DB），
 * 无 DB（repository 为 null）时回退到扫描文件 metadata 构造索引。
 * 正文/资源统一委托 SkillStore。
 */
public class DefaultSkillLoader implements SkillLoader {

    private final SkillStore skillStore;
    private final SkillIndexRepository skillIndexRepository; // nullable

    public DefaultSkillLoader(SkillStore skillStore, SkillIndexRepository skillIndexRepository) {
        this.skillStore = skillStore;
        this.skillIndexRepository = skillIndexRepository;
    }

    @Override
    public List<SkillIndex> loadActiveIndex() {
        if (skillIndexRepository != null) {
            return skillIndexRepository.findActive();
        }
        return skillStore.listAll().stream()
                .filter(m -> m.getStatus() != SkillStatus.ARCHIVED)
                .map(SkillIndex::fromMetadata)
                .toList();
    }

    @Override
    public Optional<Skill> loadSkill(String skillId) {
        return skillStore.load(skillId);
    }

    @Override
    public Optional<SkillContent> loadContent(String skillId) {
        return skillStore.loadContent(skillId);
    }

    @Override
    public String loadResource(String skillId, String relativePath) {
        return skillStore.readFile(skillId, relativePath);
    }

    @Override
    public List<String> listResources(String skillId, String subDir) {
        return skillStore.listResources(skillId, subDir);
    }
}
