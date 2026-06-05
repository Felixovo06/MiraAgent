package com.felix.miraagent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.stream.Collectors;

public class AsyncEmbeddingIndexer {

    private static final Logger log = LoggerFactory.getLogger(AsyncEmbeddingIndexer.class);

    private final JdbcTemplate jdbc;
    private final EmbeddingClient embeddingClient;

    public AsyncEmbeddingIndexer(JdbcTemplate jdbc, EmbeddingClient embeddingClient) {
        this.jdbc = jdbc;
        this.embeddingClient = embeddingClient;
    }

    public void indexAsync(String memoryId, String content) {
        if (embeddingClient == null) {
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                persist(memoryId, embeddingClient.embed(content));
            } catch (EmbeddingException e) {
                log.warn("Failed to compute embedding for memory {}: {}", memoryId, e.getMessage());
            } catch (Exception e) {
                log.warn("Unexpected error indexing embedding for memory {}", memoryId, e);
            }
        });
    }

    /**
     * 同步持久化已算好的向量。写入路径已在后台 writer 线程上、不在主回复链路，
     * 故此处同步落库以消除“写入后向量未就绪、向量召回漏掉新卡”的一致性窗口。
     * 失败仅记日志、不抛：embedding 缺失只降级到词法召回，绝不阻断写入。
     */
    public void persist(String memoryId, List<Float> vector) {
        if (vector == null || vector.isEmpty()) {
            return;
        }
        try {
            jdbc.update("UPDATE memory_index SET embedding = ?::vector WHERE id = ?",
                    toVectorString(vector), memoryId);
        } catch (Exception e) {
            log.warn("Failed to persist embedding for memory {}", memoryId, e);
        }
    }

    private String toVectorString(List<Float> vec) {
        return "[" + vec.stream().map(Object::toString).collect(Collectors.joining(",")) + "]";
    }
}
