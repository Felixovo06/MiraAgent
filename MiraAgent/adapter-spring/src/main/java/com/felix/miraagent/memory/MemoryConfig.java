package com.felix.miraagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.felix.miraagent.memory.jdbc.JdbcHybridMemoryRetriever;
import com.felix.miraagent.memory.jdbc.JdbcMemoryIndexRepository;
import com.felix.miraagent.memory.jdbc.JdbcMemoryRetriever;
import com.felix.miraagent.memory.jdbc.MemoryIndexRebuildService;
import com.felix.miraagent.config.UsableDataSourceCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

@Configuration
@EnableConfigurationProperties({MemoryProperties.class, EmbeddingProperties.class, RerankProperties.class})
public class MemoryConfig {

    @Bean
    public MemoryStore memoryFileStore(MemoryProperties memoryProperties) {
        return new MemoryFileStore(memoryProperties.getBaseDir());
    }

    @Bean
    public MemoryWritePolicy memoryWritePolicy() {
        return new InMemoryMemoryWritePolicy();
    }

    @Bean
    public SerializedMemoryWriter blockingQueueMemoryWriter(MemoryStore memoryStore,
                                                            Optional<MemoryIndexRepository> memoryIndexRepository,
                                                            Optional<AsyncEmbeddingIndexer> asyncEmbeddingIndexer,
                                                            Optional<MemoryWritePolicy> memoryWritePolicy,
                                                            Optional<EmbeddingClient> embeddingClient,
                                                            MemoryProperties memoryProperties) {
        MemoryProperties.Dedup dedup = memoryProperties.getDedup();
        // 去重需索引支持；关闭去重时把 exact 阈值置 0 走直通
        double near = dedup.isEnabled() ? dedup.getNearThreshold() : 0;
        double exact = dedup.isEnabled() ? dedup.getExactThreshold() : 0;
        double semNear = dedup.isEnabled() ? dedup.getSemanticNearThreshold() : 0;
        double semExact = dedup.isEnabled() ? dedup.getSemanticExactThreshold() : 0;
        return new BlockingQueueMemoryWriter(memoryStore,
                memoryIndexRepository.orElse(null),
                asyncEmbeddingIndexer.orElse(null),
                memoryWritePolicy.orElse(null),
                embeddingClient.orElse(null),
                near, exact, semNear, semExact);
    }

    @Bean
    @Conditional(UsableDataSourceCondition.class)
    public MemoryIndexRepository memoryIndexRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new JdbcMemoryIndexRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    @Conditional(UsableDataSourceCondition.class)
    public MemoryIndexRebuildService memoryIndexRebuildService(MemoryStore memoryStore,
                                                               MemoryIndexRepository memoryIndexRepository,
                                                               MemoryProperties memoryProperties) {
        return new MemoryIndexRebuildService(memoryStore, memoryIndexRepository, memoryProperties);
    }

    @Bean
    @Conditional(UsableDataSourceCondition.class)
    public JdbcMemoryRetriever jdbcLexicalRetriever(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        return new JdbcMemoryRetriever(jdbc, objectMapper);
    }

    @Bean
    @Primary
    @Conditional(UsableDataSourceCondition.class)
    public MemoryRetriever hybridMemoryRetriever(JdbcMemoryRetriever lexical,
                                                 JdbcTemplate jdbc,
                                                 ObjectMapper objectMapper,
                                                 Optional<EmbeddingClient> embeddingClient,
                                                 Optional<RerankClient> rerankClient,
                                                 RerankProperties rerankProperties) {
        return new JdbcHybridMemoryRetriever(lexical, embeddingClient.orElse(null), jdbc, objectMapper,
                rerankClient.orElse(null), rerankProperties.getTopN());
    }

    @Bean
    @ConditionalOnProperty(prefix = "mira.rerank", name = "base-url")
    public RerankClient rerankClient(RerankProperties props) {
        return new DashScopeRerankClient(props);
    }

    @Bean
    @Conditional(UsableDataSourceCondition.class)
    public AsyncEmbeddingIndexer asyncEmbeddingIndexer(JdbcTemplate jdbc, Optional<EmbeddingClient> embeddingClient) {
        return new AsyncEmbeddingIndexer(jdbc, embeddingClient.orElse(null));
    }

    @Bean
    @ConditionalOnProperty(prefix = "mira.embedding", name = "base-url")
    public EmbeddingClient embeddingClient(EmbeddingProperties props) {
        return new OpenAICompatibleEmbeddingClient(props);
    }
}
