package com.felix.miraagent.api.controller;

import com.felix.miraagent.api.service.TraceApiService;
import com.felix.miraagent.trace.TraceEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/traces")
public class TraceController {

    private final TraceApiService traceApiService;

    public TraceController(TraceApiService traceApiService) {
        this.traceApiService = traceApiService;
    }

    @GetMapping("/{runId}")
    public ResponseEntity<List<TraceEvent>> getTrace(@PathVariable String runId) {
        return ResponseEntity.ok(traceApiService.getTrace(runId));
    }
}
