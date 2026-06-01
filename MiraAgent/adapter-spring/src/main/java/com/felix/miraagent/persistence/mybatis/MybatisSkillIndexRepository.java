package com.felix.miraagent.persistence.mybatis;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.felix.miraagent.persistence.entity.SkillEntity;
import com.felix.miraagent.persistence.mapper.SkillMapper;
import com.felix.miraagent.skill.SkillIndex;
import com.felix.miraagent.skill.SkillIndexRepository;
import com.felix.miraagent.skill.SkillStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * skills 索引表的 MyBatis-Plus 实现（镜像 Mybatis*Store）。
 * 向量去重查询（cosine <=>）将由 step4 的自定义 SQL 补充，不走 BaseMapper。
 */
public class MybatisSkillIndexRepository implements SkillIndexRepository {

    private static final Logger log = LoggerFactory.getLogger(MybatisSkillIndexRepository.class);

    private final SkillMapper skillMapper;
    private final ObjectMapper objectMapper;

    public MybatisSkillIndexRepository(SkillMapper skillMapper, ObjectMapper objectMapper) {
        this.skillMapper = skillMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(SkillIndex index) {
        SkillEntity entity = toEntity(index);
        Instant now = Instant.now();
        if (skillMapper.selectById(index.getSkillId()) != null) {
            entity.setUpdatedAt(now);
            skillMapper.updateById(entity);
        } else {
            entity.setCreatedAt(index.getCreatedAt() != null ? index.getCreatedAt() : now);
            entity.setUpdatedAt(now);
            skillMapper.insert(entity);
        }
    }

    @Override
    public void archive(String skillId) {
        SkillEntity entity = skillMapper.selectById(skillId);
        if (entity == null) {
            return;
        }
        Instant now = Instant.now();
        entity.setStatus(SkillStatus.ARCHIVED.name());
        entity.setArchivedAt(now);
        entity.setUpdatedAt(now);
        skillMapper.updateById(entity);
    }

    @Override
    public List<SkillIndex> findActive() {
        return skillMapper.selectList(Wrappers.<SkillEntity>lambdaQuery()
                        .eq(SkillEntity::getStatus, SkillStatus.ACTIVE.name())
                        .isNull(SkillEntity::getArchivedAt)
                        .orderByAsc(SkillEntity::getCreatedAt))
                .stream()
                .map(this::toIndex)
                .toList();
    }

    @Override
    public Optional<SkillIndex> findById(String skillId) {
        SkillEntity entity = skillMapper.selectById(skillId);
        return entity == null ? Optional.empty() : Optional.of(toIndex(entity));
    }

    @Override
    public void deleteAll() {
        skillMapper.delete(Wrappers.emptyWrapper());
    }

    private SkillEntity toEntity(SkillIndex index) {
        SkillEntity entity = new SkillEntity();
        entity.setId(index.getSkillId());
        entity.setName(index.getName());
        entity.setDescription(index.getDescription());
        entity.setStatus(index.getStatus() != null ? index.getStatus().name() : SkillStatus.ACTIVE.name());
        if (index.getTags() != null && !index.getTags().isEmpty()) {
            try {
                entity.setTags(objectMapper.writeValueAsString(index.getTags()));
            } catch (Exception e) {
                log.warn("Failed to serialize tags for skill {}", index.getSkillId(), e);
            }
        }
        entity.setPinned(index.isPinned());
        entity.setUseCount(index.getUseCount());
        entity.setVersion(index.getVersion());
        entity.setSourceUri(index.getSourceUri());
        entity.setSourceTraceId(index.getSourceTraceId());
        entity.setSourceSessionId(index.getSourceSessionId());
        entity.setEmbeddingRef(index.getEmbeddingRef());
        entity.setLastUsedAt(index.getLastUsedAt());
        entity.setArchivedAt(index.getArchivedAt());
        return entity;
    }

    private SkillIndex toIndex(SkillEntity entity) {
        List<String> tags = Collections.emptyList();
        if (entity.getTags() != null) {
            try {
                tags = objectMapper.readValue(entity.getTags(), new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("Failed to deserialize tags for skill {}", entity.getId(), e);
            }
        }
        return SkillIndex.builder()
                .skillId(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .status(entity.getStatus() != null ? SkillStatus.valueOf(entity.getStatus()) : null)
                .tags(tags)
                .pinned(Boolean.TRUE.equals(entity.getPinned()))
                .useCount(entity.getUseCount() != null ? entity.getUseCount() : 0)
                .version(entity.getVersion() != null ? entity.getVersion() : 1)
                .sourceUri(entity.getSourceUri())
                .sourceTraceId(entity.getSourceTraceId())
                .sourceSessionId(entity.getSourceSessionId())
                .embeddingRef(entity.getEmbeddingRef())
                .lastUsedAt(entity.getLastUsedAt())
                .archivedAt(entity.getArchivedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
