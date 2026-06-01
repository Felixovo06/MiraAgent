package com.felix.miraagent.api.service;

import com.felix.miraagent.trace.TraceEvent;
import com.felix.miraagent.trace.TraceStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TraceApiService {

    private final TraceStore traceStore;

    public TraceApiService(TraceStore traceStore) {
        this.traceStore = traceStore;
    }

    public List<TraceEvent> getTrace(String runId) {
        return traceStore.findByRunId(runId);
    }
}
