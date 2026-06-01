package com.felix.miraagent.tools.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.felix.miraagent.memory.MemoryRetrieveRequest;
import com.felix.miraagent.memory.MemoryRetrieveResult;
import com.felix.miraagent.memory.MemoryRetriever;
import com.felix.miraagent.tools.ToolDefinition;
import com.felix.miraagent.tools.ToolExecutionResult;
import com.felix.miraagent.tools.ToolHandler;
import com.felix.miraagent.tools.ToolRiskLevel;

import java.util.Map;

public class RecallMemoryToolHandler implements ToolHandler {

    private final MemoryRetriever retriever;

    public RecallMemoryToolHandler(MemoryRetriever retriever) {
        this.retriever = retriever;
    }

    public static ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("recall_memory")
                .description("Search long-term memory for relevant facts. Call this when the user references past events, people, or preferences.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "Keywords or phrase to search for"),
                                "character_id", Map.of("type", "string", "description", "Optional: filter by character")),
                        "required", new String[]{"query"}))
                .riskLevel(ToolRiskLevel.LOW)
                .build();
    }

    @Override
    public ToolExecutionResult execute(String toolCallId, JsonNode arguments) {
        try {
            String query = arguments.path("query").asText("");
            String characterId = arguments.has("character_id") ? arguments.path("character_id").asText(null) : null;

            MemoryRetrieveRequest request = MemoryRetrieveRequest.builder()
                    .query(query)
                    .characterId(characterId)
                    .limit(10)
                    .build();

            MemoryRetrieveResult result = retriever.retrieve(request);

            if (result.getHits() == null || result.getHits().isEmpty()) {
                return ToolExecutionResult.success(toolCallId, "recall_memory", "No relevant memories found.");
            }

            StringBuilder sb = new StringBuilder();
            for (var hit : result.getHits()) {
                sb.append("- [").append(hit.getCategory()).append("] ").append(hit.getContentPreview()).append("\n");
            }
            return ToolExecutionResult.success(toolCallId, "recall_memory", sb.toString());
        } catch (Exception e) {
            return ToolExecutionResult.error(toolCallId, "recall_memory", e.getMessage());
        }
    }
}
