package com.felix.miraagent.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.felix.miraagent.eval.EvalRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 评测触发/结果 REST（前端 Dashboard 用）。默认开启，可 mira.eval.enabled=false 关闭。
 * <p>调用 eval 模块的 {@link EvalRunner} 把"运行中的本服务"当黑盒跑一遍用例集——
 * eval 模块本身仍零依赖 core/adapter，这里只是触发入口。异步运行，前端轮询结果。
 */
@RestController
@RequestMapping("/api/eval")
@ConditionalOnProperty(name = "mira.eval.enabled", havingValue = "true", matchIfMissing = true)
public class EvalController {

    private static final Logger log = LoggerFactory.getLogger(EvalController.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<JsonNode> lastReport = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();

    @Value("${server.port:8080}")
    private int serverPort;

    @PostMapping("/run")
    public ResponseEntity<?> run() {
        if (!running.compareAndSet(false, true)) {
            return ResponseEntity.ok(Map.of("status", "running"));
        }
        lastError.set(null);
        String selfUrl = "http://localhost:" + serverPort;
        Thread.ofVirtual().start(() -> {
            try {
                ObjectNode report = new EvalRunner().buildReport(selfUrl, "eval/cases.json", null);
                lastReport.set(report);
            } catch (Exception e) {
                log.warn("Eval run failed", e);
                lastError.set(e.getMessage());
            } finally {
                running.set(false);
            }
        });
        return ResponseEntity.ok(Map.of("status", "started"));
    }

    @GetMapping("/report")
    public ResponseEntity<?> report() {
        ObjectNode body = mapper.createObjectNode();
        body.put("running", running.get());
        if (lastError.get() != null) {
            body.put("error", lastError.get());
        }
        body.set("report", lastReport.get()); // 尚无报告时为 null
        return ResponseEntity.ok(body);
    }
}
