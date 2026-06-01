package com.felix.miraagent.tools.impl;

import com.felix.miraagent.tools.ToolDefinition;
import com.felix.miraagent.tools.ToolHandler;
import com.felix.miraagent.tools.ToolRegistry;
import com.felix.miraagent.tools.ToolResolveContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryToolRegistry implements ToolRegistry {

    private final Map<String, ToolDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, ToolHandler> handlers = new ConcurrentHashMap<>();

    @Override
    public void register(ToolDefinition definition, ToolHandler handler) {
        definitions.put(definition.getName(), definition);
        handlers.put(definition.getName(), handler);
    }

    @Override
    public List<ToolDefinition> listAvailable(ToolResolveContext context) {
        if (context.getEnabledToolNames().isEmpty()) {
            return new ArrayList<>(definitions.values());
        }
        return definitions.values().stream()
                .filter(d -> context.getEnabledToolNames().contains(d.getName()))
                .toList();
    }

    @Override
    public Optional<ToolHandler> findHandler(String toolName) {
        return Optional.ofNullable(handlers.get(toolName));
    }

    @Override
    public Optional<ToolDefinition> findDefinition(String toolName) {
        return Optional.ofNullable(definitions.get(toolName));
    }
}
