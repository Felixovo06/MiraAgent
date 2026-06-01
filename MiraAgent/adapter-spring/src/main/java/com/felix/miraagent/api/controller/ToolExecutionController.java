package com.felix.miraagent.api.controller;

import com.felix.miraagent.api.service.ToolExecutionApiService;
import com.felix.miraagent.tools.ToolExecutionRecord;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tool-executions")
public class ToolExecutionController {
    private final ToolExecutionApiService toolExecutionApiService;

    public ToolExecutionController(ToolExecutionApiService toolExecutionApiService) {
        this.toolExecutionApiService = toolExecutionApiService;
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<List<ToolExecutionRecord>> byRun(@PathVariable String runId) {
        return ResponseEntity.ok(toolExecutionApiService.findByRunId(runId));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<List<ToolExecutionRecord>> bySession(@PathVariable String sessionId) {
        return ResponseEntity.ok(toolExecutionApiService.findBySessionId(sessionId));
    }
}
