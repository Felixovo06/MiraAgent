package com.felix.miraagent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillUsageTrackerTest {

    private SkillFileStore store;
    private DefaultSkillUsageTracker tracker;
    private Path baseDir;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        this.baseDir = tmp;
        this.store = new SkillFileStore(tmp.toString(), new ObjectMapper().findAndRegisterModules());
        this.tracker = new DefaultSkillUsageTracker(store);
        Instant now = Instant.now();
        store.write(Skill.builder()
                .metadata(SkillMetadata.builder()
                        .skillId("code-review").name("代码审查").description("d")
                        .status(SkillStatus.ACTIVE).version(1).pinned(false)
                        .createdAt(now).updatedAt(now).build())
                .content(SkillContent.of("代码审查", "d", "步骤"))
                .build());
    }

    @Test
    void recordUseIncrementsCountAndTimestamp() {
        var m1 = tracker.recordUse("code-review", "trace-1", "sess-1").orElseThrow();
        assertEquals(1, m1.getUseCount());
        assertNotNull(m1.getLastUsedAt());

        var m2 = tracker.recordUse("code-review", "trace-2", "sess-1").orElseThrow();
        assertEquals(2, m2.getUseCount());
    }

    @Test
    void recordViewAndPatchTracked() {
        tracker.recordView("code-review", "t", "s");
        var afterPatch = tracker.recordPatch("code-review", "t", "修正步骤2").orElseThrow();
        assertEquals(1, afterPatch.getViewCount());
        assertEquals(1, afterPatch.getPatchCount());
        // patch 提升版本号
        assertEquals(2, afterPatch.getVersion());
        assertNotNull(afterPatch.getLastPatchedAt());
    }

    @Test
    void historyGrowsPerEvent() throws Exception {
        tracker.recordView("code-review", "t", "s");
        tracker.recordUse("code-review", "t", "s");
        tracker.recordPatch("code-review", "t", "n");
        List<String> history = Files.readAllLines(baseDir.resolve("code-review/history.jsonl"));
        // created(1) + viewed + used + patched = 4
        assertEquals(4, history.size());
        assertTrue(history.get(0).contains("CREATED"));
        assertTrue(history.get(1).contains("VIEWED"));
        assertTrue(history.get(2).contains("USED"));
        assertTrue(history.get(3).contains("PATCHED"));
        // 来源引用落盘
        assertTrue(history.get(2).contains("trace") || history.get(2).contains("sourceTraceId"));
    }

    @Test
    void pinnedSkillNotAutoArchivable() {
        var pinned = tracker.setPinned("code-review", true).orElseThrow();
        assertTrue(pinned.isPinned());
        assertFalse(tracker.canAutoArchive(pinned));

        var unpinned = tracker.setPinned("code-review", false).orElseThrow();
        assertTrue(tracker.canAutoArchive(unpinned));
    }

    @Test
    void recordOnMissingSkillReturnsEmpty() {
        assertTrue(tracker.recordUse("nope", "t", "s").isEmpty());
    }
}
