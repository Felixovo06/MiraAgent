package com.felix.miraagent.memory.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.felix.miraagent.memory.MemoryCategory;
import com.felix.miraagent.memory.MemoryIndex;
import com.felix.miraagent.memory.MemoryRetrieveRequest;
import com.felix.miraagent.memory.MemoryRetrieveResult;
import com.felix.miraagent.memory.MemoryRetriever;
import com.felix.miraagent.memory.MemoryScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

public class JdbcMemoryRetriever implements MemoryRetriever {

    private static final Logger log = LoggerFactory.getLogger(JdbcMemoryRetriever.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcMemoryRetriever(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public MemoryRetrieveResult retrieve(MemoryRetrieveRequest request) {
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            return MemoryRetrieveResult.builder()
                    .hits(Collections.emptyList())
                    .queryUsed(request.getQuery())
                    .build();
        }

        String q = request.getQuery();
        String userId = request.getUserId();
        int limit = request.getLimit() > 0 ? request.getLimit() : 10;

        String sql;
        Object[] params;

        if (request.getCharacterId() != null) {
            sql = """
                    SELECT DISTINCT m.id, m.user_id, m.character_id, m.scope, m.category,
                        m.content_preview, m.source_uri, m.confidence,
                        m.source_session_id, m.source_message_id,
                        m.retrieval_terms, m.embedding_ref, m.archived_at,
                        m.created_at, m.updated_at,
                        similarity(m.content_preview, ?) as trgm_score,
                        ts_rank(to_tsvector('simple', coalesce(m.content_preview, '')), plainto_tsquery('simple', ?)) as fts_score
                    FROM memory_index m
                    WHERE m.user_id = ? AND m.character_id = ? AND m.archived_at IS NULL
                      AND (
                        m.content_preview % ?
                        OR to_tsvector('simple', coalesce(m.content_preview, '')) @@ plainto_tsquery('simple', ?)
                      )
                    ORDER BY (similarity(m.content_preview, ?) + ts_rank(to_tsvector('simple', coalesce(m.content_preview, '')), plainto_tsquery('simple', ?))) DESC
                    LIMIT ?
                    """;
            params = new Object[]{q, q, userId, request.getCharacterId(), q, q, q, q, limit};
        } else {
            sql = """
                    SELECT DISTINCT m.id, m.user_id, m.character_id, m.scope, m.category,
                        m.content_preview, m.source_uri, m.confidence,
                        m.source_session_id, m.source_message_id,
                        m.retrieval_terms, m.embedding_ref, m.archived_at,
                        m.created_at, m.updated_at,
                        similarity(m.content_preview, ?) as trgm_score,
                        ts_rank(to_tsvector('simple', coalesce(m.content_preview, '')), plainto_tsquery('simple', ?)) as fts_score
                    FROM memory_index m
                    WHERE m.user_id = ? AND m.archived_at IS NULL
                      AND (
                        m.content_preview % ?
                        OR to_tsvector('simple', coalesce(m.content_preview, '')) @@ plainto_tsquery('simple', ?)
                      )
                    ORDER BY (similarity(m.content_preview, ?) + ts_rank(to_tsvector('simple', coalesce(m.content_preview, '')), plainto_tsquery('simple', ?))) DESC
                    LIMIT ?
                    """;
            params = new Object[]{q, q, userId, q, q, q, q, limit};
        }

        List<MemoryIndex> hits = jdbc.query(sql, memoryIndexRowMapper(), params);

        return MemoryRetrieveResult.builder()
                .hits(hits)
                .queryUsed(q)
                .build();
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
