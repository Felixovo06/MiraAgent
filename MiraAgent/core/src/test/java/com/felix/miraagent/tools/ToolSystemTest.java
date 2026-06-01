package com.felix.miraagent.tools;

import com.felix.miraagent.model.ToolCall;
import com.felix.miraagent.tools.builtin.BuiltinTools;
import com.felix.miraagent.tools.impl.DefaultToolDispatcher;
import com.felix.miraagent.tools.impl.DefaultToolPermissionPolicy;
import com.felix.miraagent.tools.impl.InMemoryToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolSystemTest {

    private InMemoryToolRegistry registry;
    private DefaultToolDispatcher dispatcher;
    private ToolDispatchContext context;

    @BeforeEach
    void setUp() {
        registry = new InMemoryToolRegistry();
        BuiltinTools.registerAll(registry);
        dispatcher = new DefaultToolDispatcher(registry);
        context = ToolDispatchContext.builder()
                .runId("run-1")
                .sessionId("session-1")
                .userId("user-1")
                .permissionPolicy(new DefaultToolPermissionPolicy())
                .build();
    }

    @Test
    void registerAndListTools() {
        var ctx = ToolResolveContext.builder().build();
        var tools = registry.listAvailable(ctx);
        assertEquals(2, tools.size());
        assertTrue(tools.stream().anyMatch(t -> t.getName().equals("note")));
        assertTrue(tools.stream().anyMatch(t -> t.getName().equals("todo")));
    }

    @Test
    void listOnlyEnabledTools() {
        var ctx = ToolResolveContext.builder().enabledToolName("note").build();
        var tools = registry.listAvailable(ctx);
        assertEquals(1, tools.size());
        assertEquals("note", tools.get(0).getName());
    }

    @Test
    void executeSingleNote() {
        var call = ToolCall.builder().id("tc1").name("note").arguments("{\"content\":\"hello world\"}").build();
        var result = dispatcher.dispatchOne(call, context);
        assertEquals(ToolStatus.SUCCESS, result.getStatus());
        assertTrue(result.getModelVisibleContent().contains("Note saved"));
    }

    @Test
    void executeSingleTodo() {
        var call = ToolCall.builder().id("tc2").name("todo").arguments("{\"task\":\"read papers\"}").build();
        var result = dispatcher.dispatchOne(call, context);
        assertEquals(ToolStatus.SUCCESS, result.getStatus());
        assertTrue(result.getModelVisibleContent().contains("read papers"));
    }

    @Test
    void unknownToolReturnsError() {
        var call = ToolCall.builder().id("tc3").name("nonexistent").arguments("{}").build();
        var result = dispatcher.dispatchOne(call, context);
        assertEquals(ToolStatus.ERROR, result.getStatus());
        assertTrue(result.getModelVisibleContent().contains("Unknown tool"));
    }

    @Test
    void toolExceptionReturnsError() {
        registry.register(
                ToolDefinition.builder().name("broken").description("always fails").riskLevel(ToolRiskLevel.LOW).build(),
                (id, args) -> { throw new RuntimeException("intentional failure"); }
        );
        var call = ToolCall.builder().id("tc4").name("broken").arguments("{}").build();
        var result = dispatcher.dispatchOne(call, context);
        assertEquals(ToolStatus.ERROR, result.getStatus());
        assertFalse(result.getModelVisibleContent().isBlank());
    }

    @Test
    void highRiskToolIsDenied() {
        registry.register(
                ToolDefinition.builder().name("dangerous").description("high risk").riskLevel(ToolRiskLevel.HIGH).build(),
                (id, args) -> ToolExecutionResult.success(id, "dangerous", "done")
        );
        var call = ToolCall.builder().id("tc5").name("dangerous").arguments("{}").build();
        var result = dispatcher.dispatchOne(call, context);
        assertEquals(ToolStatus.DENIED, result.getStatus());
    }

    @Test
    void multipleToolsPreserveOrder() {
        var calls = List.of(
                ToolCall.builder().id("tc1").name("note").arguments("{\"content\":\"first\"}").build(),
                ToolCall.builder().id("tc2").name("todo").arguments("{\"task\":\"second\"}").build(),
                ToolCall.builder().id("tc3").name("note").arguments("{\"content\":\"third\"}").build()
        );
        var results = dispatcher.dispatchAll(calls, context);
        assertEquals(3, results.size());
        assertEquals("tc1", results.get(0).getToolCallId());
        assertEquals("tc2", results.get(1).getToolCallId());
        assertEquals("tc3", results.get(2).getToolCallId());
        assertTrue(results.stream().allMatch(r -> r.getStatus() == ToolStatus.SUCCESS));
    }
}
