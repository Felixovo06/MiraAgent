package com.felix.miraagent.skill;

import java.util.List;
import java.util.Optional;

/**
 * Skill 文件事实源端口（镜像 MemoryStore）：SKILL.md / metadata.json / history.jsonl
 * 与 references/、examples/ 的低层文件 IO。所有写入由 step4 的单 writer 串行化调用。
 */
public interface SkillStore {

    /** 创建或覆盖一个 skill（写 SKILL.md + metadata.json，确保子目录存在）。 */
    SkillWriteResult write(Skill skill);

    /** 逻辑归档（metadata.status=ARCHIVED），非硬删除。 */
    SkillWriteResult archive(String skillId);

    /** 加载完整 skill（metadata + 正文）。 */
    Optional<Skill> load(String skillId);

    /** 仅加载 SKILL.md 正文（渐进式披露）。 */
    Optional<SkillContent> loadContent(String skillId);

    /** 读取 references/ 或 examples/ 下的某个资源文件，相对 skill 目录。 */
    String readFile(String skillId, String relativePath);

    /** 列出 skill 目录下某子目录（references/examples）的文件名。 */
    List<String> listResources(String skillId, String subDir);

    /** 仅加载 metadata.json（不读正文）。 */
    Optional<SkillMetadata> loadMetadata(String skillId);

    /** 仅覆盖写 metadata.json（不动 SKILL.md，不追加 history）。用于统计/pinned 更新。 */
    void saveMetadata(SkillMetadata metadata);

    /** 追加一条事件到 history.jsonl。 */
    void appendHistory(String skillId, SkillUsageEvent event);

    /** 枚举所有 skill 的 metadata（用于索引重建）。 */
    List<SkillMetadata> listAll();
}
