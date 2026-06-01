package com.felix.miraagent.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.felix.miraagent.tools.ToolDefinition;
import com.felix.miraagent.tools.ToolExecutionResult;
import com.felix.miraagent.tools.ToolHandler;
import com.felix.miraagent.tools.ToolRiskLevel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 文件写入工具（HIGH 风险，沙箱内写）。属危险工具——默认被 RiskThresholdToolPermissionPolicy
 * 门控（阈值 MEDIUM 时返回 DENIED），需把 mira.tools.max-risk-level 抬到 high 才放行。
 */
public class FileWriteToolHandler implements ToolHandler {

    public static final String NAME = "file_write";

    private final FileSandbox sandbox;

    public FileWriteToolHandler(String baseDir) {
        this.sandbox = new FileSandbox(baseDir);
    }

    public static ToolDefinition definition() {
        return ToolDefinition.builder()
                .name(NAME)
                .description("Write UTF-8 text to a file in the sandboxed workspace (overwrites). Dangerous: gated by permission policy.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "Relative path within the workspace"),
                                "content", Map.of("type", "string",
                                        "description", "The text content to write")),
                        "required", new String[]{"path", "content"}))
                .riskLevel(ToolRiskLevel.HIGH)
                .build();
    }

    @Override
    public ToolExecutionResult execute(String toolCallId, JsonNode arguments) {
        String relative = arguments.path("path").asText("");
        String content = arguments.path("content").asText("");
        try {
            Path file = sandbox.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return ToolExecutionResult.success(toolCallId, NAME,
                    "Wrote " + content.getBytes(StandardCharsets.UTF_8).length + " bytes to " + relative);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.error(toolCallId, NAME, e.getMessage());
        } catch (Exception e) {
            return ToolExecutionResult.error(toolCallId, NAME, "Write failed: " + e.getMessage());
        }
    }
}
