package com.felix.miraagent.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.felix.miraagent.model.ToolCall;
import com.felix.miraagent.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class DefaultToolDispatcher implements ToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolDispatcher.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ToolRegistry registry;

    public DefaultToolDispatcher(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<ToolExecutionResult> dispatchAll(List<ToolCall> calls, ToolDispatchContext context) {
        List<ToolExecutionResult> results = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            results.add(dispatchOne(call, context));
        }
        return results;
    }

    @Override
    public ToolExecutionResult dispatchOne(ToolCall call, ToolDispatchContext context) {
        log.debug("Dispatching tool={} callId={} runId={}", call.getName(), call.getId(), context.getRunId());

        var definitionOpt = registry.findDefinition(call.getName());
        if (definitionOpt.isEmpty()) {
            return ToolExecutionResult.error(call.getId(), call.getName(),
                    "Unknown tool: " + call.getName());
        }

        var definition = definitionOpt.get();

        var permissionPolicy = context.getPermissionPolicy();
        if (permissionPolicy != null && !permissionPolicy.isAllowed(definition, context)) {
            log.info("Tool permission denied tool={} callId={}", call.getName(), call.getId());
            return ToolExecutionResult.denied(call.getId(), call.getName(),
                    "Tool '" + call.getName() + "' requires higher permission level.");
        }

        var handlerOpt = registry.findHandler(call.getName());
        if (handlerOpt.isEmpty()) {
            return ToolExecutionResult.error(call.getId(), call.getName(),
                    "No handler registered for tool: " + call.getName());
        }

        try {
            JsonNode arguments = parseArguments(call.getArguments());
            return handlerOpt.get().execute(call.getId(), arguments);
        } catch (Exception e) {
            log.warn("Tool execution failed tool={} callId={}", call.getName(), call.getId(), e);
            return ToolExecutionResult.error(call.getId(), call.getName(), e.getMessage());
        }
    }

    private JsonNode parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(argumentsJson);
        } catch (Exception e) {
            log.warn("Failed to parse tool arguments: {}", argumentsJson);
            return objectMapper.createObjectNode();
        }
    }
}
