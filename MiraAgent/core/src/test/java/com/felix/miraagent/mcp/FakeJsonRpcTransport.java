package com.felix.miraagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 内存版 JSON-RPC 传输，模拟一个 MCP server，无需子进程即可单测 McpClient/Bridge/Adapter。
 */
class FakeJsonRpcTransport implements JsonRpcTransport {

    private final ObjectMapper mapper = new ObjectMapper();
    final List<String> requestedMethods = new ArrayList<>();
    final List<String> notifiedMethods = new ArrayList<>();
    boolean closed = false;
    boolean failNextCall = false;
    /** 为 true 时 tools/call 抛异常，直到 reconnect() 被调用，用于验证客户端自愈。 */
    boolean breakConnection = false;
    int reconnects = 0;

    @Override
    public JsonNode request(String method, JsonNode params) throws McpException {
        requestedMethods.add(method);
        if (breakConnection && "tools/call".equals(method)) {
            throw new McpException("connection broken");
        }
        switch (method) {
            case "initialize" -> {
                ObjectNode r = mapper.createObjectNode();
                r.put("protocolVersion", "2024-11-05");
                r.set("capabilities", mapper.createObjectNode());
                return r;
            }
            case "tools/list" -> {
                ObjectNode r = mapper.createObjectNode();
                ArrayNode tools = r.putArray("tools");
                ObjectNode echo = tools.addObject();
                echo.put("name", "echo");
                echo.put("description", "Echo back the message");
                ObjectNode schema = echo.putObject("inputSchema");
                schema.put("type", "object");
                ObjectNode props = schema.putObject("properties");
                props.putObject("message").put("type", "string");
                schema.putArray("required").add("message");
                return r;
            }
            case "tools/call" -> {
                if (failNextCall) {
                    throw new McpException("simulated transport failure");
                }
                String name = params.path("name").asText();
                if (!"echo".equals(name)) {
                    ObjectNode r = mapper.createObjectNode();
                    r.put("isError", true);
                    ArrayNode content = r.putArray("content");
                    content.addObject().put("type", "text").put("text", "unknown tool: " + name);
                    return r;
                }
                String msg = params.path("arguments").path("message").asText("");
                ObjectNode r = mapper.createObjectNode();
                r.put("isError", false);
                ArrayNode content = r.putArray("content");
                content.addObject().put("type", "text").put("text", "echo: " + msg);
                return r;
            }
            default -> throw new McpException("unexpected method: " + method);
        }
    }

    @Override
    public void notify(String method, JsonNode params) {
        notifiedMethods.add(method);
    }

    @Override
    public boolean isHealthy() {
        return !closed && !breakConnection;
    }

    @Override
    public void reconnect() {
        reconnects++;
        breakConnection = false;
    }

    @Override
    public void close() {
        closed = true;
    }
}
