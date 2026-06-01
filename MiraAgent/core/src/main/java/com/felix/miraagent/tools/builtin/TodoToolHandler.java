package com.felix.miraagent.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.felix.miraagent.tools.ToolExecutionResult;
import com.felix.miraagent.tools.ToolHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TodoToolHandler implements ToolHandler {

    private final List<String> todos = Collections.synchronizedList(new ArrayList<>());

    @Override
    public ToolExecutionResult execute(String toolCallId, JsonNode arguments) {
        String task = arguments.path("task").asText("");
        if (task.isBlank()) {
            return ToolExecutionResult.error(toolCallId, "todo", "Missing required field: task");
        }
        todos.add(task);
        return ToolExecutionResult.success(toolCallId, "todo",
                "Todo added: \"" + task + "\". Total todos: " + todos.size());
    }

    public List<String> getTodos() {
        return Collections.unmodifiableList(todos);
    }
}
