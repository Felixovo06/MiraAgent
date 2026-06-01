package com.felix.miraagent.persistence.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.felix.miraagent.persistence.jdbc.JdbcSessionStore;
import com.felix.miraagent.persistence.jdbc.JdbcTraceStore;
import com.felix.miraagent.persistence.jdbc.JdbcToolExecutionStore;
import com.felix.miraagent.session.SessionStore;
import com.felix.miraagent.tools.ToolExecutionStore;
import com.felix.miraagent.trace.TraceStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
@ConditionalOnBean(DataSource.class)
public class PersistenceConfig {

    @Bean
    public SessionStore sessionStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new JdbcSessionStore(jdbcTemplate, objectMapper);
    }

    @Bean
    public TraceStore traceStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new JdbcTraceStore(jdbcTemplate, objectMapper);
    }

    @Bean
    public ToolExecutionStore toolExecutionStore(JdbcTemplate jdbcTemplate) {
        return new JdbcToolExecutionStore(jdbcTemplate);
    }
}
