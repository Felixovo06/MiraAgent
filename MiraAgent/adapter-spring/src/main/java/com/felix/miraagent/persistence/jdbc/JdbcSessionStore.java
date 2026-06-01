package com.felix.miraagent.persistence.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.felix.miraagent.model.Message;
import com.felix.miraagent.model.MessageRole;
import com.felix.miraagent.model.ToolCall;
import com.felix.miraagent.session.Session;
import com.felix.miraagent.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class JdbcSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcSessionStore.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcSessionStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Session createSession(Session session) {
        jdbc.update("""
                insert into sessions (id, user_id, character_id, title, source, created_at, updated_at)
                values (?, ?, ?, ?, ?, now(), now())
                on conflict (id) do nothing
                """,
                session.getId(), session.getUserId(), session.getCharacterId(),
                session.getTitle(), session.getSource());
        return session;
    }

    @Override
    public Optional<Session> findById(String sessionId) {
        var rows = jdbc.query(
                "select id, user_id, character_id, title, source, created_at, updated_at from sessions where id = ?",
                sessionRowMapper(), sessionId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void appendMessage(String sessionId, Message message) {
        String toolCallsJson = null;
        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            try {
                toolCallsJson = objectMapper.writeValueAsString(message.getToolCalls());
            } catch (Exception e) {
                log.warn("Failed to serialize tool_calls for message {}", message.getId(), e);
            }
        }
        jdbc.update("""
                insert into messages (id, session_id, role, content, tool_call_id, tool_name, tool_calls, created_at)
                values (?, ?, ?, ?, ?, ?, cast(? as jsonb), now())
                """,
                message.getId() != null ? message.getId() : UUID.randomUUID().toString(),
                sessionId,
                message.getRole().name().toLowerCase(),
                message.getContent(),
                message.getToolCallId(),
                message.getToolName(),
                toolCallsJson);
    }

    @Override
    public List<Message> loadMessages(String sessionId) {
        return jdbc.query(
                "select id, role, content, tool_call_id, tool_name, tool_calls, created_at from messages where session_id = ? order by created_at",
                messageRowMapper(), sessionId);
    }

    @Override
    public void updateLastMessageAt(String sessionId) {
        jdbc.update("update sessions set last_message_at = now(), updated_at = now() where id = ?", sessionId);
    }

    private RowMapper<Session> sessionRowMapper() {
        return (rs, rowNum) -> Session.builder()
                .id(rs.getString("id"))
                .userId(rs.getString("user_id"))
                .characterId(rs.getString("character_id"))
                .title(rs.getString("title"))
                .source(rs.getString("source"))
                .build();
    }

    private RowMapper<Message> messageRowMapper() {
        return (rs, rowNum) -> {
            String roleStr = rs.getString("role").toUpperCase();
            MessageRole role = MessageRole.valueOf(roleStr);
            var builder = Message.builder()
                    .id(rs.getString("id"))
                    .role(role)
                    .content(rs.getString("content"))
                    .toolCallId(rs.getString("tool_call_id"))
                    .toolName(rs.getString("tool_name"));

            String toolCallsJson = rs.getString("tool_calls");
            if (toolCallsJson != null) {
                try {
                    List<ToolCall> toolCalls = objectMapper.readValue(toolCallsJson, new TypeReference<>() {});
                    toolCalls.forEach(builder::toolCall);
                } catch (Exception e) {
                    log.warn("Failed to deserialize tool_calls for message {}", rs.getString("id"), e);
                }
            }
            return builder.build();
        };
    }
}
