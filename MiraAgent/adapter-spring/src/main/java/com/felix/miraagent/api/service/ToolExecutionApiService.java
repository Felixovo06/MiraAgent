package com.felix.miraagent.api.service;

import com.felix.miraagent.tools.ToolExecutionRecord;
import com.felix.miraagent.tools.ToolExecutionStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ToolExecutionApiService {

    private final ToolExecutionStore toolExecutionStore;

    public ToolExecutionApiService(ToolExecutionStore toolExecutionStore) {
        this.toolExecutionStore = toolExecutionStore;
    }

    public List<ToolExecutionRecord> findByRunId(String runId) {
        return toolExecutionStore.findByRunId(runId);
    }

    public List<ToolExecutionRecord> findBySessionId(String sessionId) {
        return toolExecutionStore.findBySessionId(sessionId);
    }
}
