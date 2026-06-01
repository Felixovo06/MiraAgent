package com.felix.miraagent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Skill 文件事实源实现（镜像 MemoryFileStore）。目录结构：
 * <pre>
 * {baseDir}/{skillId}/
 *     SKILL.md
 *     metadata.json
 *     history.jsonl
 *     references/
 *     examples/
 * </pre>
 * metadata.json 为索引/状态/统计/来源的事实源；SKILL.md 为正文事实源。不保存用户隐私事实。
 */
public class SkillFileStore implements SkillStore {

    private static final Logger log = LoggerFactory.getLogger(SkillFileStore.class);
    private static final String SKILL_MD = "SKILL.md";
    private static final String METADATA_JSON = "metadata.json";
    private static final String HISTORY_JSONL = "history.jsonl";

    private final Path baseDir;
    private final ObjectMapper objectMapper;

    public SkillFileStore(String baseDir, ObjectMapper objectMapper) {
        this.baseDir = Paths.get(baseDir);
        this.objectMapper = objectMapper;
    }

    @Override
    public SkillWriteResult write(Skill skill) {
        SkillMetadata metadata = skill.getMetadata();
        if (metadata == null || metadata.getSkillId() == null) {
            return SkillWriteResult.builder()
                    .success(false)
                    .error("skill metadata or skillId is null")
                    .build();
        }
        String skillId = metadata.getSkillId();
        try {
            Path dir = skillDir(skillId);
            boolean isNew = !Files.exists(dir);
            Files.createDirectories(dir.resolve("references"));
            Files.createDirectories(dir.resolve("examples"));

            SkillContent content = skill.getContent();
            if (content == null) {
                content = SkillContent.of(metadata.getName(), metadata.getDescription(), "");
            }
            String raw = content.getRaw() != null ? content.getRaw()
                    : SkillContent.of(content.getName(), content.getDescription(), content.getBody()).getRaw();
            Files.writeString(dir.resolve(SKILL_MD), raw, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            Files.writeString(dir.resolve(METADATA_JSON), objectMapper.writeValueAsString(metadata),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            appendHistory(skillId, SkillUsageEvent.builder()
                    .type(isNew ? SkillUsageEventType.CREATED : SkillUsageEventType.UPDATED)
                    .skillId(skillId).at(Instant.now()).build());

            return SkillWriteResult.builder()
                    .skillId(skillId)
                    .sourceUri(baseDir.relativize(dir.resolve(SKILL_MD)).toString())
                    .success(true)
                    .build();
        } catch (IOException e) {
            return SkillWriteResult.builder()
                    .skillId(skillId)
                    .success(false)
                    .error(e.getMessage())
                    .build();
        }
    }

    @Override
    public SkillWriteResult archive(String skillId) {
        Path dir = skillDir(skillId);
        Path metaPath = dir.resolve(METADATA_JSON);
        if (!Files.exists(metaPath)) {
            return SkillWriteResult.builder()
                    .skillId(skillId)
                    .success(false)
                    .error("skill not found")
                    .build();
        }
        try {
            Instant now = Instant.now();
            SkillMetadata current = objectMapper.readValue(Files.readString(metaPath, StandardCharsets.UTF_8),
                    SkillMetadata.class);
            SkillMetadata archived = current.toBuilder()
                    .status(SkillStatus.ARCHIVED)
                    .updatedAt(now)
                    .build();
            Files.writeString(metaPath, objectMapper.writeValueAsString(archived),
                    StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            appendHistory(skillId, SkillUsageEvent.of(SkillUsageEventType.ARCHIVED, skillId, now));
            return SkillWriteResult.builder()
                    .skillId(skillId)
                    .sourceUri(baseDir.relativize(dir.resolve(SKILL_MD)).toString())
                    .success(true)
                    .build();
        } catch (IOException e) {
            return SkillWriteResult.builder()
                    .skillId(skillId)
                    .success(false)
                    .error(e.getMessage())
                    .build();
        }
    }

    @Override
    public Optional<Skill> load(String skillId) {
        Optional<SkillMetadata> metadata = loadMetadata(skillId);
        if (metadata.isEmpty()) {
            return Optional.empty();
        }
        SkillContent content = loadContent(skillId).orElse(null);
        return Optional.of(Skill.builder().metadata(metadata.get()).content(content).build());
    }

    @Override
    public Optional<SkillContent> loadContent(String skillId) {
        Path mdPath = skillDir(skillId).resolve(SKILL_MD);
        if (!Files.exists(mdPath)) {
            return Optional.empty();
        }
        try {
            return Optional.of(SkillContent.parse(Files.readString(mdPath, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read SKILL.md for " + skillId, e);
        }
    }

    @Override
    public String readFile(String skillId, String relativePath) {
        Path dir = skillDir(skillId).normalize();
        Path target = dir.resolve(relativePath).normalize();
        // 防目录穿越：必须仍在 skill 目录内
        if (!target.startsWith(dir)) {
            throw new IllegalArgumentException("Illegal resource path: " + relativePath);
        }
        if (!Files.exists(target) || Files.isDirectory(target)) {
            return "";
        }
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read skill resource: " + target, e);
        }
    }

    @Override
    public List<String> listResources(String skillId, String subDir) {
        Path dir = skillDir(skillId).normalize();
        Path target = dir.resolve(subDir).normalize();
        if (!target.startsWith(dir) || !Files.isDirectory(target)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(target)) {
            return stream.filter(p -> !Files.isDirectory(p))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list resources for skill {} subDir {}", skillId, subDir, e);
            return List.of();
        }
    }

    @Override
    public Optional<SkillMetadata> loadMetadata(String skillId) {
        Path metaPath = skillDir(skillId).resolve(METADATA_JSON);
        if (!Files.exists(metaPath)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(Files.readString(metaPath, StandardCharsets.UTF_8),
                    SkillMetadata.class));
        } catch (IOException e) {
            log.warn("Failed to read metadata.json for skill {}", skillId, e);
            return Optional.empty();
        }
    }

    @Override
    public synchronized void saveMetadata(SkillMetadata metadata) {
        if (metadata == null || metadata.getSkillId() == null) {
            return;
        }
        Path metaPath = skillDir(metadata.getSkillId()).resolve(METADATA_JSON);
        if (!Files.exists(metaPath.getParent())) {
            throw new IllegalStateException("skill dir not found: " + metadata.getSkillId());
        }
        try {
            Files.writeString(metaPath, objectMapper.writeValueAsString(metadata),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write metadata.json for " + metadata.getSkillId(), e);
        }
    }

    @Override
    public void appendHistory(String skillId, SkillUsageEvent event) {
        Path dir = skillDir(skillId);
        if (!Files.exists(dir)) {
            return;
        }
        try {
            Files.writeString(dir.resolve(HISTORY_JSONL), objectMapper.writeValueAsString(event) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to append history for skill {}", skillId, e);
        }
    }

    @Override
    public List<SkillMetadata> listAll() {
        if (!Files.exists(baseDir)) {
            return List.of();
        }
        List<SkillMetadata> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(baseDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                loadMetadata(dir.getFileName().toString()).ifPresent(result::add);
            }
        } catch (IOException e) {
            log.warn("Failed to list skills under {}", baseDir, e);
        }
        return result;
    }

    private Path skillDir(String skillId) {
        return baseDir.resolve(skillId);
    }
}
