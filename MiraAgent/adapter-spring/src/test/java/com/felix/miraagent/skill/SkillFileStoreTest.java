package com.felix.miraagent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SkillFileStoreTest {

    private SkillFileStore store;
    private Path baseDir;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        this.baseDir = tmp;
        this.store = new SkillFileStore(tmp.toString(), new ObjectMapper().findAndRegisterModules());
    }

    private Skill sampleSkill(String id) {
        Instant now = Instant.now();
        SkillMetadata metadata = SkillMetadata.builder()
                .skillId(id)
                .name("code-review")
                .description("系统化代码审查流程")
                .status(SkillStatus.ACTIVE)
                .source("user")
                .sourceTraceId("trace-1")
                .version(1)
                .tags(List.of("review", "quality"))
                .pinned(false)
                .createdAt(now)
                .updatedAt(now)
                .build();
        SkillContent content = SkillContent.of("code-review", "系统化代码审查流程",
                "## 何时使用\n复杂改动审查\n## 执行步骤\n1. 读 diff\n2. 检查测试");
        return Skill.builder().metadata(metadata).content(content).build();
    }

    @Test
    void writeThenLoadFullSkill() {
        SkillWriteResult result = store.write(sampleSkill("code-review"));
        assertTrue(result.isSuccess());
        assertEquals("code-review/SKILL.md", result.getSourceUri());

        Optional<Skill> loaded = store.load("code-review");
        assertTrue(loaded.isPresent());
        assertEquals("code-review", loaded.get().getSkillId());
        assertEquals(SkillStatus.ACTIVE, loaded.get().getMetadata().getStatus());
        assertEquals(List.of("review", "quality"), loaded.get().getMetadata().getTags());
        // 正文 frontmatter 解析
        assertEquals("code-review", loaded.get().getContent().getName());
        assertEquals("系统化代码审查流程", loaded.get().getContent().getDescription());
        assertTrue(loaded.get().getContent().getBody().contains("执行步骤"));
    }

    @Test
    void loadContentOnly() {
        store.write(sampleSkill("code-review"));
        Optional<SkillContent> content = store.loadContent("code-review");
        assertTrue(content.isPresent());
        assertTrue(content.get().getRaw().startsWith("---\n"));
        assertTrue(content.get().getBody().contains("读 diff"));
    }

    @Test
    void readReferencesAndExamples() throws Exception {
        store.write(sampleSkill("code-review"));
        // references/ 与 examples/ 应已创建
        assertTrue(Files.isDirectory(baseDir.resolve("code-review/references")));
        assertTrue(Files.isDirectory(baseDir.resolve("code-review/examples")));

        Files.writeString(baseDir.resolve("code-review/references/checklist.md"),
                "- 命名\n- 边界", StandardCharsets.UTF_8);
        Files.writeString(baseDir.resolve("code-review/examples/good.md"),
                "示例", StandardCharsets.UTF_8);

        assertEquals("- 命名\n- 边界", store.readFile("code-review", "references/checklist.md"));
        assertEquals(List.of("checklist.md"), store.listResources("code-review", "references"));
        assertEquals(List.of("good.md"), store.listResources("code-review", "examples"));
    }

    @Test
    void readFileRejectsPathTraversal() {
        store.write(sampleSkill("code-review"));
        assertThrows(IllegalArgumentException.class,
                () -> store.readFile("code-review", "../../etc/passwd"));
    }

    @Test
    void archiveFlipsStatus() {
        store.write(sampleSkill("code-review"));
        SkillWriteResult archived = store.archive("code-review");
        assertTrue(archived.isSuccess());
        assertEquals(SkillStatus.ARCHIVED, store.load("code-review").orElseThrow().getMetadata().getStatus());
    }

    @Test
    void listAllReturnsAllMetadata() {
        store.write(sampleSkill("code-review"));
        store.write(sampleSkill("write-tests"));
        List<SkillMetadata> all = store.listAll();
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(m -> m.getSkillId().equals("code-review")));
        assertTrue(all.stream().anyMatch(m -> m.getSkillId().equals("write-tests")));
    }

    @Test
    void historyAppendedOnWriteAndArchive() throws Exception {
        store.write(sampleSkill("code-review"));
        store.archive("code-review");
        List<String> history = Files.readAllLines(baseDir.resolve("code-review/history.jsonl"));
        assertEquals(2, history.size());
        assertTrue(history.get(0).contains("created"));
        assertTrue(history.get(1).contains("archived"));
    }
}
