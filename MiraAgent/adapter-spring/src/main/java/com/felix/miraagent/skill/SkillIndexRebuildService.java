package com.felix.miraagent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 从文件事实源（metadata.json）重建 skills 索引表（镜像 MemoryIndexRebuildService）。
 * skill 是 Agent 全局能力，全量重建：先清空索引，再按 metadata 逐个写入。
 */
public class SkillIndexRebuildService {

    private static final Logger log = LoggerFactory.getLogger(SkillIndexRebuildService.class);

    private final SkillStore skillStore;
    private final SkillIndexRepository skillIndexRepository;

    public SkillIndexRebuildService(SkillStore skillStore, SkillIndexRepository skillIndexRepository) {
        this.skillStore = skillStore;
        this.skillIndexRepository = skillIndexRepository;
    }

    public void rebuild() {
        log.info("Starting skill index rebuild");
        skillIndexRepository.deleteAll();
        List<SkillMetadata> all = skillStore.listAll();
        for (SkillMetadata metadata : all) {
            skillIndexRepository.save(SkillIndex.fromMetadata(metadata));
        }
        log.info("Skill index rebuild complete: {} skills indexed", all.size());
    }
}
