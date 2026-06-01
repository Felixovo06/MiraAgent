package com.felix.miraagent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.felix.miraagent.config.UsableDataSourceCondition;
import com.felix.miraagent.persistence.mapper.SkillMapper;
import com.felix.miraagent.persistence.mybatis.MybatisSkillIndexRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Skill 子系统装配（镜像 MemoryConfig）。
 * 索引仓库 conditional on usable DataSource：无 DB 时 SkillLoader 回退扫描文件 metadata。
 */
@Configuration
@EnableConfigurationProperties(SkillProperties.class)
public class SkillConfig {

    @Bean
    public SkillStore skillFileStore(SkillProperties props, ObjectMapper objectMapper) {
        return new SkillFileStore(props.getBaseDir(), objectMapper);
    }

    @Bean
    @Conditional(UsableDataSourceCondition.class)
    public SkillIndexRepository skillIndexRepository(SkillMapper skillMapper, ObjectMapper objectMapper) {
        return new MybatisSkillIndexRepository(skillMapper, objectMapper);
    }

    @Bean
    public SkillLoader skillLoader(SkillStore skillStore,
                                   java.util.Optional<SkillIndexRepository> skillIndexRepository) {
        return new DefaultSkillLoader(skillStore, skillIndexRepository.orElse(null));
    }

    @Bean
    public SkillUsageTracker skillUsageTracker(SkillStore skillStore) {
        return new DefaultSkillUsageTracker(skillStore);
    }

    @Bean
    @Conditional(UsableDataSourceCondition.class)
    public SkillIndexRebuildService skillIndexRebuildService(SkillStore skillStore,
                                                             SkillIndexRepository skillIndexRepository) {
        return new SkillIndexRebuildService(skillStore, skillIndexRepository);
    }
}
