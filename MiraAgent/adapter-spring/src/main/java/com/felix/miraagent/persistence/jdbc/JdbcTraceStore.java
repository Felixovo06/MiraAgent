package com.felix.miraagent.persistence.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.felix.miraagent.trace.TraceEvent;
import com.felix.miraagent.trace.TraceEventType;
import com.felix.miraagent.trace.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

public class JdbcTraceStore implements TraceStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcTraceStore.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcTraceStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(TraceEvent event) {
        String payloadJson = null;
        if (event.getPayload() != null) {
            try {
                payloadJson = objectMapper.writeValueAsString(event.getPayload());
            } catch (Exception e) {
                log.warn("Failed to serialize trace payload for event {}", event.getId(), e);
            }
        }
        jdbc.update("""
                insert into agent_traces (id, run_id, session_id, step_index, event_type, payload, created_at)
                values (?, ?, ?, ?, ?, cast(? as jsonb), now())
                """,
                event.getId(), event.getRunId(), event.getSessionId(),
                event.getStepIndex(), event.getEventType().name(),
                payloadJson);
    }

    @Override
    public List<TraceEvent> findByRunId(String runId) {
        return jdbc.query(
                "select id, run_id, session_id, step_index, event_type, payload from agent_traces where run_id = ? order by step_index",
                traceRowMapper(), runId);
    }

    @Override
    public List<TraceEvent> findBySessionId(String sessionId) {
        return jdbc.query(
                "select id, run_id, session_id, step_index, event_type, payload from agent_traces where session_id = ? order by created_at",
                traceRowMapper(), sessionId);
    }

    private RowMapper<TraceEvent> traceRowMapper() {
        return (rs, rowNum) -> {
            Map<String, Object> payload = null;
            String payloadJson = rs.getString("payload");
            if (payloadJson != null) {
                try {
                    payload = objectMapper.readValue(payloadJson, new TypeReference<>() {});
                } catch (Exception e) {
                    log.warn("Failed to deserialize trace payload for event {}", rs.getString("id"), e);
                }
            }
            return TraceEvent.builder()
                    .id(rs.getString("id"))
                    .runId(rs.getString("run_id"))
                    .sessionId(rs.getString("session_id"))
                    .stepIndex(rs.getInt("step_index"))
                    .eventType(TraceEventType.valueOf(rs.getString("event_type")))
                    .payload(payload)
                    .build();
        };
    }
}
