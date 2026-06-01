package com.felix.miraagent.api.controller;

import com.felix.miraagent.api.service.RunApiService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunApiService runApiService;

    public RunController(RunApiService runApiService) {
        this.runApiService = runApiService;
    }

    @PostMapping("/{runId}/interrupt")
    public ResponseEntity<Map<String, String>> interrupt(@PathVariable String runId) {
        return ResponseEntity.ok(runApiService.interrupt(runId));
    }
}
