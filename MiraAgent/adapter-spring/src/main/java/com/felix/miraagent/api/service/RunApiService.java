package com.felix.miraagent.api.service;

import com.felix.miraagent.agent.AgentRuntime;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class RunApiService {

    private final AgentRuntime agentRuntime;

    public RunApiService(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    public Map<String, String> interrupt(String runId) {
        agentRuntime.interrupt(runId);
        return Map.of("message", "interrupt signal sent", "runId", runId);
    }
}
