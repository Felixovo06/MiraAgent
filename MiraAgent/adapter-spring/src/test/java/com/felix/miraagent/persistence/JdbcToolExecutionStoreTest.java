package com.felix.miraagent.persistence;

import com.felix.miraagent.model.ToolCall;
import com.felix.miraagent.persistence.jdbc.JdbcToolExecutionStore;
import com.felix.miraagent.tools.ToolExecutionResult;
import com.felix.miraagent.tools.ToolStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

class JdbcToolExecutionStoreTest {
    private JdbcToolExecutionStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:tool_exec;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                create table if not exists tool_executions (
                    id text primary key,
                    run_id text not null,
                    session_id text not null,
                    tool_call_id text not null,
                    tool_name text not null,
                    arguments jsonb,
                    status text not null,
                    model_visible_content text,
                    error_message text,
                    started_at timestamp with time zone not null,
                    finished_at timestamp with time zone
                )
                """);
        store = new JdbcToolExecutionStore(jdbc);
    }

    @Test
    void recordsAndFindsToolExecutionByRun() {
        ToolCall call = ToolCall.builder()
                .id("tc1")
                .name("note")
                .arguments("{\"content\":\"hello\"}")
                .build();
        ToolExecutionResult result = ToolExecutionResult.success("tc1", "note", "Note saved: hello");

        store.record("run-1", "session-1", call, result);

        var records = store.findByRunId("run-1");
        assertEquals(1, records.size());
        assertEquals("tc1", records.get(0).getToolCallId());
        assertEquals("note", records.get(0).getToolName());
        assertEquals(ToolStatus.SUCCESS, records.get(0).getStatus());
        assertTrue(records.get(0).getArguments().contains("hello"));
    }
}
