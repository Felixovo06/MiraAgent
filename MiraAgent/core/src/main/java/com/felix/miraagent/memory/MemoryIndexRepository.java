package com.felix.miraagent.memory;

import java.util.List;
import java.util.Optional;

public interface MemoryIndexRepository {

    void save(MemoryIndex index);

    void archive(String userId, String memoryId);

    /**
     * Find non-archived memory indexes for a user.
     * characterId and category are optional filters; pass null to skip filtering.
     */
    List<MemoryIndex> findByUser(String userId, String characterId, String category);

    Optional<MemoryIndex> findById(String id);

    /**
     * 写入前去重用：在同 user + characterId + category 范围内，找出与 content 最相似的未归档记忆。
     * 仅返回相似度 >= minSimilarity 的最高匹配；characterId 为 null 时仅匹配 character_id 为 null 的记忆。
     * 不支持相似度查询的实现可返回 {@link Optional#empty()}。
     */
    Optional<SimilarMemory> findMostSimilar(String userId, String characterId, MemoryCategory category,
                                            String content, double minSimilarity);

    /**
     * 写入前语义去重用：在同 user + characterId + category 范围内，找出 embedding 与给定向量
     * cosine 相似度最高且 >= minSimilarity 的未归档记忆。补字面 trgm 漏掉的同义改写。
     * 不支持向量检索的实现返回 {@link Optional#empty()}。
     */
    default Optional<SimilarMemory> findMostSimilarByVector(String userId, String characterId, MemoryCategory category,
                                                            List<Float> embedding, double minSimilarity) {
        return Optional.empty();
    }

    /** Delete all indexes for a user (used during full rebuild). */
    void deleteAll(String userId);
}
