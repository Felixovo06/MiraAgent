package com.felix.miraagent.tools;

import com.felix.miraagent.model.ToolCall;

import java.util.List;

public interface ToolExecutionStore {
    void record(String runId, String sessionId, ToolCall call, ToolExecutionResult result);

    List<ToolExecutionRecord> findByRunId(String runId);

    List<ToolExecutionRecord> findBySessionId(String sessionId);
}
