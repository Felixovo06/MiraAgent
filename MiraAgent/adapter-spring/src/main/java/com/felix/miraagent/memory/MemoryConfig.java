package com.felix.miraagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.felix.miraagent.memory.jdbc.JdbcMemoryIndexRepository;
import com.felix.miraagent.memory.jdbc.JdbcMemoryRetriever;
import com.felix.miraagent.memory.jdbc.MemoryIndexRebuildService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryConfig {

    @Bean
    public MemoryStore memoryFileStore(MemoryProperties memoryProperties) {
        return new MemoryFileStore(memoryProperties.getBaseDir());
    }

    @Bean
    public SerializedMemoryWriter blockingQueueMemoryWriter(MemoryStore memoryStore) {
        return new BlockingQueueMemoryWriter(memoryStore);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    public MemoryIndexRepository memoryIndexRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new JdbcMemoryIndexRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    public MemoryIndexRebuildService memoryIndexRebuildService(MemoryStore memoryStore,
                                                               MemoryIndexRepository memoryIndexRepository,
                                                               MemoryProperties memoryProperties) {
        return new MemoryIndexRebuildService(memoryStore, memoryIndexRepository, memoryProperties);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    public MemoryRetriever memoryRetriever(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new JdbcMemoryRetriever(jdbcTemplate, objectMapper);
    }
}
