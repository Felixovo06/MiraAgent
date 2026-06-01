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
 * 文件读取工具（MEDIUM 风险，沙箱内只读）。长结果由 ConversationLoop 中央外置为 artifact。
 */
public class FileReadToolHandler implements ToolHandler {

    public static final String NAME = "file_read";
    private static final int MAX_CHARS = 20_000;

    private final FileSandbox sandbox;

    public FileReadToolHandler(String baseDir) {
        this.sandbox = new FileSandbox(baseDir);
    }

    public static ToolDefinition definition() {
        return ToolDefinition.builder()
                .name(NAME)
                .description("Read a UTF-8 text file from the sandboxed workspace by relative path.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string",
                                        "description", "Relative path within the workspace")),
                        "required", new String[]{"path"}))
                .riskLevel(ToolRiskLevel.MEDIUM)
                .build();
    }

    @Override
    public ToolExecutionResult execute(String toolCallId, JsonNode arguments) {
        String relative = arguments.path("path").asText("");
        try {
            Path file = sandbox.resolve(relative);
            if (!Files.exists(file) || Files.isDirectory(file)) {
                return ToolExecutionResult.error(toolCallId, NAME, "File not found: " + relative);
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.length() > MAX_CHARS) {
                content = content.substring(0, MAX_CHARS) + "\n...[truncated]";
            }
            return ToolExecutionResult.success(toolCallId, NAME, content);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.error(toolCallId, NAME, e.getMessage());
        } catch (Exception e) {
            return ToolExecutionResult.error(toolCallId, NAME, "Read failed: " + e.getMessage());
        }
    }
}
