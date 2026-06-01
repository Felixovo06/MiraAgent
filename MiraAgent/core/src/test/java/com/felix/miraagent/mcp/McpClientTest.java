package com.felix.miraagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.felix.miraagent.model.ToolCall;
import com.felix.miraagent.tools.ToolDispatchContext;
import com.felix.miraagent.tools.ToolExecutionResult;
import com.felix.miraagent.tools.ToolRiskLevel;
import com.felix.miraagent.tools.ToolStatus;
import com.felix.miraagent.tools.impl.DefaultToolDispatcher;
import com.felix.miraagent.tools.impl.DefaultToolPermissionPolicy;
import com.felix.miraagent.tools.impl.InMemoryToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private DefaultMcpClient client(FakeJsonRpcTransport transport) {
        McpServerConfig config = McpServerConfig.builder()
                .id("fake").transportType(McpTransportType.STDIO)
                .toolRiskLevel(ToolRiskLevel.LOW)
                .build();
        return new DefaultMcpClient(config, transport, mapper);
    }

    @Test
    void handshakeSendsInitializeAndNotification() {
        var transport = new FakeJsonRpcTransport();
        client(transport).initialize();
        assertTrue(transport.requestedMethods.contains("initialize"));
        assertTrue(transport.notifiedMethods.contains("notifications/initialized"));
    }

    @Test
    void initializeIsIdempotent() {
        var transport = new FakeJsonRpcTransport();
        var c = client(transport);
        c.initialize();
        c.initialize();
        assertEquals(1, transport.requestedMethods.stream().filter("initialize"::equals).count());
    }

    @Test
    void listToolsParsesNameDescriptionAndSchema() {
        var c = client(new FakeJsonRpcTransport());
        List<McpToolDescriptor> tools = c.listTools();
        assertEquals(1, tools.size());
        var echo = tools.get(0);
        assertEquals("echo", echo.getName());
        assertEquals("Echo back the message", echo.getDescription());
        assertEquals("object", echo.getInputSchema().get("type"));
    }

    @Test
    void callToolReturnsConcatenatedText() {
        var c = client(new FakeJsonRpcTransport());
        ObjectNode args = mapper.createObjectNode();
        args.put("message", "hi");
        McpToolResult result = c.callTool("echo", args);
        assertFalse(result.isError());
        assertEquals("echo: hi", result.getText());
    }

    @Test
    void callToolMarksServerSideError() {
        var c = client(new FakeJsonRpcTransport());
        McpToolResult result = c.callTool("nope", mapper.createObjectNode());
        assertTrue(result.isError());
        assertTrue(result.getText().contains("unknown tool"));
    }

    @Test
    void bridgeRegistersNamespacedToolIntoSharedRegistry() {
        var registry = new InMemoryToolRegistry();
        var c = client(new FakeJsonRpcTransport());
        var bridge = new McpToolRegistryBridge();

        var registered = bridge.registerAll(List.of(c), registry);

        assertEquals(1, registered.size());
        assertEquals("mcp__fake__echo", registered.get(0).exposedName());
        assertTrue(registry.findDefinition("mcp__fake__echo").isPresent());
        assertEquals(ToolRiskLevel.LOW,
                registry.findDefinition("mcp__fake__echo").get().getRiskLevel());
        assertTrue(registry.findHandler("mcp__fake__echo").isPresent());
    }

    @Test
    void mcpToolDispatchesThroughUnifiedDispatcherAndPermission() {
        var registry = new InMemoryToolRegistry();
        new McpToolRegistryBridge().registerAll(List.of(client(new FakeJsonRpcTransport())), registry);
        var dispatcher = new DefaultToolDispatcher(registry);

        ToolCall call = ToolCall.builder()
                .id("call-1").name("mcp__fake__echo")
                .arguments("{\"message\":\"unified\"}")
                .build();
        ToolDispatchContext ctx = ToolDispatchContext.builder()
                .runId("run-1").sessionId("s1").userId("u1")
                .permissionPolicy(new DefaultToolPermissionPolicy())
                .build();

        ToolExecutionResult result = dispatcher.dispatchOne(call, ctx);
        assertEquals(ToolStatus.SUCCESS, result.getStatus());
        assertEquals("echo: unified", result.getModelVisibleContent());
    }

    @Test
    void adapterConvertsTransportFailureToErrorResult() {
        var transport = new FakeJsonRpcTransport();
        transport.failNextCall = true;
        var adapter = new McpToolAdapter(client(transport), "echo", "mcp__fake__echo");
        ToolExecutionResult result = adapter.execute("c1", mapper.createObjectNode());
        assertEquals(ToolStatus.ERROR, result.getStatus());
        assertNotNull(result.getError());
    }

    @Test
    void callToolRecoversByReconnectingAndReinitializing() {
        var transport = new FakeJsonRpcTransport();
        var c = client(transport);
        c.initialize();
        transport.breakConnection = true;   // 下次 tools/call 失败

        ObjectNode args = mapper.createObjectNode();
        args.put("message", "after-recovery");
        McpToolResult result = c.callTool("echo", args);

        assertEquals(1, transport.reconnects, "应触发一次重连");
        assertFalse(result.isError());
        assertEquals("echo: after-recovery", result.getText());
        // 重连后应重新握手
        assertEquals(2, transport.requestedMethods.stream().filter("initialize"::equals).count());
    }

    @Test
    void highRiskMcpToolDeniedByDefaultPolicy() {
        var registry = new InMemoryToolRegistry();
        McpServerConfig config = McpServerConfig.builder()
                .id("danger").toolRiskLevel(ToolRiskLevel.HIGH).build();
        var c = new DefaultMcpClient(config, new FakeJsonRpcTransport(), mapper);
        new McpToolRegistryBridge().registerAll(List.of(c), registry);
        var dispatcher = new DefaultToolDispatcher(registry);

        ToolCall call = ToolCall.builder().id("c").name("mcp__danger__echo")
                .arguments("{\"message\":\"x\"}").build();
        ToolDispatchContext ctx = ToolDispatchContext.builder()
                .runId("r").permissionPolicy(new DefaultToolPermissionPolicy()).build();

        assertEquals(ToolStatus.DENIED, dispatcher.dispatchOne(call, ctx).getStatus());
    }
}
