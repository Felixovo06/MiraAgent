package com.felix.miraagent.memory.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.felix.miraagent.memory.MemoryCategory;
import com.felix.miraagent.memory.MemoryIndex;
import com.felix.miraagent.memory.MemoryIndexRepository;
import com.felix.miraagent.memory.MemoryScope;
import com.felix.miraagent.memory.SimilarMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class JdbcMemoryIndexRepository implements MemoryIndexRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcMemoryIndexRepository.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcMemoryIndexRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(MemoryIndex index) {
        String retrievalTermsJson = null;
        if (index.getRetrievalTerms() != null && !index.getRetrievalTerms().isEmpty()) {
            try {
                retrievalTermsJson = objectMapper.writeValueAsString(index.getRetrievalTerms());
            } catch (Exception e) {
                log.warn("Failed to serialize retrieval_terms for index {}", index.getId(), e);
            }
        }

        jdbc.update("""
                insert into memory_index (
                    id, user_id, character_id, scope, category,
                    content_preview, source_uri, confidence,
                    source_session_id, source_message_id,
                    retrieval_terms, embedding_ref, archived_at,
                    created_at, updated_at
                ) values (
                    ?, ?, ?, ?, ?,
                    ?, ?, ?,
                    ?, ?,
                    cast(? as jsonb), ?, ?,
                    now(), now()
                )
                on conflict (id) do update set
                    user_id           = excluded.user_id,
                    character_id      = excluded.character_id,
                    scope             = excluded.scope,
                    category          = excluded.category,
                    content_preview   = excluded.content_preview,
                    source_uri        = excluded.source_uri,
                    confidence        = excluded.confidence,
                    source_session_id = excluded.source_session_id,
                    source_message_id = excluded.source_message_id,
                    retrieval_terms   = excluded.retrieval_terms,
                    embedding_ref     = excluded.embedding_ref,
                    archived_at       = excluded.archived_at,
                    updated_at        = now()
                """,
                index.getId(),
                index.getUserId(),
                index.getCharacterId(),
                index.getScope() != null ? index.getScope().name() : null,
                index.getCategory() != null ? index.getCategory().name() : null,
                index.getContentPreview(),
                index.getSourceUri(),
                index.getConfidence(),
                index.getSourceSessionId(),
                index.getSourceMessageId(),
                retrievalTermsJson,
                index.getEmbeddingRef(),
                index.getArchivedAt() != null ? Timestamp.from(index.getArchivedAt()) : null
        );
    }

    @Override
    public void archive(String userId, String memoryId) {
        jdbc.update(
                "update memory_index set archived_at = now(), updated_at = now() where id = ? and user_id = ?",
                memoryId, userId);
    }

    @Override
    public List<MemoryIndex> findByUser(String userId, String characterId, String category) {
        StringBuilder sql = new StringBuilder("""
                select id, user_id, character_id, scope, category,
                       content_preview, source_uri, confidence,
                       source_session_id, source_message_id,
                       retrieval_terms, embedding_ref, archived_at,
                       created_at, updated_at
                from memory_index
                where user_id = ? and archived_at is null
                """);

        List<Object> params = new ArrayList<>();
        params.add(userId);

        if (characterId != null) {
            sql.append(" and character_id = ?");
            params.add(characterId);
        }
        if (category != null) {
            sql.append(" and category = ?");
            params.add(category);
        }

        sql.append(" order by created_at");

        return jdbc.query(sql.toString(), memoryIndexRowMapper(), params.toArray());
    }

    @Override
    public Optional<MemoryIndex> findById(String id) {
        var rows = jdbc.query("""
                select id, user_id, character_id, scope, category,
                       content_preview, source_uri, confidence,
                       source_session_id, source_message_id,
                       retrieval_terms, embedding_ref, archived_at,
                       created_at, updated_at
                from memory_index where id = ?
                """,
                memoryIndexRowMapper(), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<SimilarMemory> findMostSimilar(String userId, String characterId, MemoryCategory category,
                                                   String content, double minSimilarity) {
        if (content == null || content.isBlank() || category == null) {
            return Optional.empty();
        }
        StringBuilder sql = new StringBuilder("""
                select id, user_id, character_id, scope, category,
                       content_preview, source_uri, confidence,
                       source_session_id, source_message_id,
                       retrieval_terms, embedding_ref, archived_at,
                       created_at, updated_at,
                       similarity(content_preview, ?) as sim
                from memory_index
                where user_id = ? and category = ? and archived_at is null
                  and similarity(content_preview, ?) >= ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(content);            // select similarity(?, ...)
        params.add(userId);
        params.add(category.name());
        params.add(content);            // where similarity(?, ...) >= ?
        params.add(minSimilarity);
        if (characterId != null) {
            sql.append(" and character_id = ?");
            params.add(characterId);
        } else {
            sql.append(" and character_id is null");
        }
        sql.append(" order by sim desc limit 1");

        RowMapper<MemoryIndex> mapper = memoryIndexRowMapper();
        var rows = jdbc.query(sql.toString(),
                (rs, rowNum) -> SimilarMemory.builder()
                        .memory(mapper.mapRow(rs, rowNum))
                        .similarity(rs.getDouble("sim"))
                        .build(),
                params.toArray());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<SimilarMemory> findMostSimilarByVector(String userId, String characterId, MemoryCategory category,
                                                           List<Float> embedding, double minSimilarity) {
        if (embedding == null || embedding.isEmpty() || category == null) {
            return Optional.empty();
        }
        String vectorStr = toVectorString(embedding);
        StringBuilder sql = new StringBuilder("""
                select id, user_id, character_id, scope, category,
                       content_preview, source_uri, confidence,
                       source_session_id, source_message_id,
                       retrieval_terms, embedding_ref, archived_at,
                       created_at, updated_at,
                       1 - (embedding <=> ?::vector) as sim
                from memory_index
                where user_id = ? and category = ? and archived_at is null
                  and embedding is not null
                  and 1 - (embedding <=> ?::vector) >= ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(vectorStr);          // select 1 - (embedding <=> ?)
        params.add(userId);
        params.add(category.name());
        params.add(vectorStr);          // where 1 - (embedding <=> ?) >= ?
        params.add(minSimilarity);
        if (characterId != null) {
            sql.append(" and character_id = ?");
            params.add(characterId);
        } else {
            sql.append(" and character_id is null");
        }
        sql.append(" order by embedding <=> ?::vector limit 1");
        params.add(vectorStr);          // order by embedding <=> ?

        RowMapper<MemoryIndex> mapper = memoryIndexRowMapper();
        var rows = jdbc.query(sql.toString(),
                (rs, rowNum) -> SimilarMemory.builder()
                        .memory(mapper.mapRow(rs, rowNum))
                        .similarity(rs.getDouble("sim"))
                        .build(),
                params.toArray());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void deleteAll(String userId) {
        jdbc.update("delete from memory_index where user_id = ?", userId);
    }

    private static String toVectorString(List<Float> vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vec.get(i));
        }
        return sb.append(']').toString();
    }

    private RowMapper<MemoryIndex> memoryIndexRowMapper() {
        return (rs, rowNum) -> {
            List<String> retrievalTerms = Collections.emptyList();
            String termsJson = rs.getString("retrieval_terms");
            if (termsJson != null) {
                try {
                    retrievalTerms = objectMapper.readValue(termsJson, new TypeReference<>() {});
                } catch (Exception e) {
                    log.warn("Failed to deserialize retrieval_terms for index {}", rs.getString("id"), e);
                }
            }

            String scopeStr = rs.getString("scope");
            MemoryScope scope = scopeStr != null ? MemoryScope.valueOf(scopeStr) : null;

            String categoryStr = rs.getString("category");
            MemoryCategory category = categoryStr != null ? MemoryCategory.valueOf(categoryStr) : null;

            Timestamp archivedTs = rs.getTimestamp("archived_at");
            Timestamp createdTs = rs.getTimestamp("created_at");
            Timestamp updatedTs = rs.getTimestamp("updated_at");

            return MemoryIndex.builder()
                    .id(rs.getString("id"))
                    .userId(rs.getString("user_id"))
                    .characterId(rs.getString("character_id"))
                    .scope(scope)
                    .category(category)
                    .contentPreview(rs.getString("content_preview"))
                    .sourceUri(rs.getString("source_uri"))
                    .confidence(rs.getInt("confidence"))
                    .sourceSessionId(rs.getString("source_session_id"))
                    .sourceMessageId(rs.getString("source_message_id"))
                    .retrievalTerms(retrievalTerms)
                    .embeddingRef(rs.getString("embedding_ref"))
                    .archivedAt(archivedTs != null ? archivedTs.toInstant() : null)
                    .createdAt(createdTs != null ? createdTs.toInstant() : Instant.now())
                    .updatedAt(updatedTs != null ? updatedTs.toInstant() : Instant.now())
                    .build();
        };
    }
}
