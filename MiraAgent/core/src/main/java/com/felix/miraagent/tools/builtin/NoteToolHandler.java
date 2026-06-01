package com.felix.miraagent.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.felix.miraagent.tools.ToolExecutionResult;
import com.felix.miraagent.tools.ToolHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NoteToolHandler implements ToolHandler {

    private final List<String> notes = Collections.synchronizedList(new ArrayList<>());

    @Override
    public ToolExecutionResult execute(String toolCallId, JsonNode arguments) {
        String content = arguments.path("content").asText("");
        if (content.isBlank()) {
            return ToolExecutionResult.error(toolCallId, "note", "Missing required field: content");
        }
        notes.add(content);
        return ToolExecutionResult.success(toolCallId, "note",
                "Note saved. Total notes: " + notes.size());
    }

    public List<String> getNotes() {
        return Collections.unmodifiableList(notes);
    }
}
