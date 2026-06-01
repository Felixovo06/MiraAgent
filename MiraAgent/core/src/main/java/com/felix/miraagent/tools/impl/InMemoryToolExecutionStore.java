package com.felix.miraagent.tools.impl;

import com.felix.miraagent.model.ToolCall;
import com.felix.miraagent.tools.ToolExecutionRecord;
import com.felix.miraagent.tools.ToolExecutionResult;
import com.felix.miraagent.tools.ToolExecutionStore;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemoryToolExecutionStore implements ToolExecutionStore {
    private final List<ToolExecutionRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void record(String runId, String sessionId, ToolCall call, ToolExecutionResult result) {
        records.add(ToolExecutionRecord.builder()
                .id(UUID.randomUUID().toString())
                .runId(runId)
                .sessionId(sessionId)
                .toolCallId(result.getToolCallId())
                .toolName(result.getToolName())
                .arguments(call != null ? call.getArguments() : null)
                .status(result.getStatus())
                .modelVisibleContent(result.getModelVisibleContent())
                .errorMessage(result.getError())
                .startedAt(result.getStartedAt() != null ? result.getStartedAt() : Instant.now())
                .finishedAt(result.getFinishedAt())
                .build());
    }

    @Override
    public List<ToolExecutionRecord> findByRunId(String runId) {
        return records.stream().filter(r -> runId.equals(r.getRunId())).toList();
    }

    @Override
    public List<ToolExecutionRecord> findBySessionId(String sessionId) {
        return records.stream().filter(r -> sessionId.equals(r.getSessionId())).toList();
    }
}
