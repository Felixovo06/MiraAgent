package com.felix.miraagent.tools.builtin;

import com.felix.miraagent.tools.ToolDefinition;
import com.felix.miraagent.tools.ToolRegistry;
import com.felix.miraagent.tools.ToolRiskLevel;

import java.util.Map;

public class BuiltinTools {

    public static void registerAll(ToolRegistry registry) {
        registry.register(noteDefinition(), new NoteToolHandler());
        registry.register(todoDefinition(), new TodoToolHandler());
    }

    public static ToolDefinition noteDefinition() {
        return ToolDefinition.builder()
                .name("note")
                .description("Save a text note to memory.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "content", Map.of("type", "string", "description", "The note content to save")),
                        "required", new String[]{"content"}))
                .riskLevel(ToolRiskLevel.LOW)
                .build();
    }

    public static ToolDefinition todoDefinition() {
        return ToolDefinition.builder()
                .name("todo")
                .description("Create a todo item.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "task", Map.of("type", "string", "description", "The task description")),
                        "required", new String[]{"task"}))
                .riskLevel(ToolRiskLevel.LOW)
                .build();
    }
}
