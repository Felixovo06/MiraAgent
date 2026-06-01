package com.felix.miraagent.persistence.jdbc;

import com.felix.miraagent.model.ToolCall;
import com.felix.miraagent.tools.ToolExecutionRecord;
import com.felix.miraagent.tools.ToolExecutionResult;
import com.felix.miraagent.tools.ToolExecutionStore;
import com.felix.miraagent.tools.ToolStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class JdbcToolExecutionStore implements ToolExecutionStore {
    private final JdbcTemplate jdbc;

    public JdbcToolExecutionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(String runId, String sessionId, ToolCall call, ToolExecutionResult result) {
        jdbc.update("""
                insert into tool_executions
                    (id, run_id, session_id, tool_call_id, tool_name, arguments, status,
                     model_visible_content, error_message, started_at, finished_at)
                values (?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                runId,
                sessionId,
                result.getToolCallId(),
                result.getToolName(),
                call != null ? call.getArguments() : null,
                result.getStatus().name(),
                result.getModelVisibleContent(),
                result.getError(),
                Timestamp.from(result.getStartedAt() != null ? result.getStartedAt() : Instant.now()),
                result.getFinishedAt() != null ? Timestamp.from(result.getFinishedAt()) : null);
    }

    @Override
    public List<ToolExecutionRecord> findByRunId(String runId) {
        return jdbc.query("""
                select id, run_id, session_id, tool_call_id, tool_name, arguments, status,
                       model_visible_content, error_message, started_at, finished_at
                from tool_executions
                where run_id = ?
                order by started_at, id
                """, rowMapper(), runId);
    }

    @Override
    public List<ToolExecutionRecord> findBySessionId(String sessionId) {
        return jdbc.query("""
                select id, run_id, session_id, tool_call_id, tool_name, arguments, status,
                       model_visible_content, error_message, started_at, finished_at
                from tool_executions
                where session_id = ?
                order by started_at, id
                """, rowMapper(), sessionId);
    }

    private RowMapper<ToolExecutionRecord> rowMapper() {
        return (rs, rowNum) -> ToolExecutionRecord.builder()
                .id(rs.getString("id"))
                .runId(rs.getString("run_id"))
                .sessionId(rs.getString("session_id"))
                .toolCallId(rs.getString("tool_call_id"))
                .toolName(rs.getString("tool_name"))
                .arguments(rs.getString("arguments"))
                .status(ToolStatus.valueOf(rs.getString("status")))
                .modelVisibleContent(rs.getString("model_visible_content"))
                .errorMessage(rs.getString("error_message"))
                .startedAt(rs.getTimestamp("started_at").toInstant())
                .finishedAt(rs.getTimestamp("finished_at") != null ? rs.getTimestamp("finished_at").toInstant() : null)
                .build();
    }
}
