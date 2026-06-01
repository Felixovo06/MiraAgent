package com.felix.miraagent.skill;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DefaultSkillLoaderTest {

    private SkillMetadata meta(String id, SkillStatus status) {
        Instant now = Instant.now();
        return SkillMetadata.builder()
                .skillId(id).name(id).description("desc-" + id)
                .status(status).version(1).pinned(false)
                .createdAt(now).updatedAt(now)
                .build();
    }

    /** 内存假 store，足以验证 loader 行为。 */
    private SkillStore fakeStore(List<SkillMetadata> all) {
        return new SkillStore() {
            @Override public SkillWriteResult write(Skill skill) { return null; }
            @Override public SkillWriteResult archive(String skillId) { return null; }
            @Override public Optional<Skill> load(String skillId) {
                return all.stream().filter(m -> m.getSkillId().equals(skillId))
                        .findFirst().map(m -> Skill.builder().metadata(m).build());
            }
            @Override public Optional<SkillContent> loadContent(String skillId) {
                return Optional.of(SkillContent.of(skillId, "d", "body-" + skillId));
            }
            @Override public String readFile(String skillId, String relativePath) { return "res:" + relativePath; }
            @Override public List<String> listResources(String skillId, String subDir) { return List.of(subDir + "-file"); }
            @Override public List<SkillMetadata> listAll() { return new ArrayList<>(all); }
        };
    }

    @Test
    void fallsBackToFileScanWhenNoRepository() {
        List<SkillMetadata> all = List.of(meta("a", SkillStatus.ACTIVE), meta("b", SkillStatus.ARCHIVED));
        DefaultSkillLoader loader = new DefaultSkillLoader(fakeStore(all), null);

        List<SkillIndex> index = loader.loadActiveIndex();
        assertEquals(1, index.size());
        assertEquals("a", index.get(0).getSkillId());
        assertEquals("skills/a/SKILL.md", index.get(0).getSourceUri());
    }

    @Test
    void usesRepositoryWhenPresent() {
        SkillIndex repoEntry = SkillIndex.builder()
                .skillId("from-repo").name("from-repo").status(SkillStatus.ACTIVE).build();
        SkillIndexRepository repo = new SkillIndexRepository() {
            @Override public void save(SkillIndex index) { }
            @Override public void archive(String skillId) { }
            @Override public List<SkillIndex> findActive() { return List.of(repoEntry); }
            @Override public Optional<SkillIndex> findById(String skillId) { return Optional.empty(); }
            @Override public void deleteAll() { }
        };
        DefaultSkillLoader loader = new DefaultSkillLoader(fakeStore(List.of(meta("a", SkillStatus.ACTIVE))), repo);

        List<SkillIndex> index = loader.loadActiveIndex();
        assertEquals(1, index.size());
        assertEquals("from-repo", index.get(0).getSkillId());
    }

    @Test
    void delegatesContentAndResourceLoading() {
        DefaultSkillLoader loader = new DefaultSkillLoader(fakeStore(List.of(meta("a", SkillStatus.ACTIVE))), null);
        assertTrue(loader.loadSkill("a").isPresent());
        assertTrue(loader.loadContent("a").orElseThrow().getBody().contains("body-a"));
        assertEquals("res:references/x.md", loader.loadResource("a", "references/x.md"));
        assertEquals(List.of("references-file"), loader.listResources("a", "references"));
    }
}
