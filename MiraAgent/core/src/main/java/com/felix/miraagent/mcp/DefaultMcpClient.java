package com.felix.miraagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 {@link JsonRpcTransport} 的 MCP client 默认实现。
 * 与具体传输（stdio/http/内存）无关，只负责 MCP 协议语义。
 */
public class DefaultMcpClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultMcpClient.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpServerConfig config;
    private final JsonRpcTransport transport;
    private final ObjectMapper objectMapper;
    private volatile boolean initialized = false;

    public DefaultMcpClient(McpServerConfig config, JsonRpcTransport transport, ObjectMapper objectMapper) {
        this.config = config;
        this.transport = transport;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpServerConfig config() {
        return config;
    }

    @Override
    public synchronized void initialize() throws McpException {
        if (initialized) {
            return;
        }
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "MiraAgent");
        clientInfo.put("version", "0.0.1");
        params.set("clientInfo", clientInfo);

        transport.request("initialize", params);
        transport.notify("notifications/initialized", objectMapper.createObjectNode());
        initialized = true;
        log.info("MCP server '{}' initialized", config.getId());
    }

    @Override
    public List<McpToolDescriptor> listTools() throws McpException {
        JsonNode result = requestWithRecovery("tools/list", objectMapper.createObjectNode());
        List<McpToolDescriptor> tools = new ArrayList<>();
        JsonNode arr = result.path("tools");
        if (arr.isArray()) {
            for (JsonNode t : arr) {
                Map<String, Object> schema = null;
                JsonNode schemaNode = t.get("inputSchema");
                if (schemaNode != null && schemaNode.isObject()) {
                    schema = objectMapper.convertValue(schemaNode, Map.class);
                }
                tools.add(McpToolDescriptor.builder()
                        .name(t.path("name").asText())
                        .description(t.path("description").asText(""))
                        .inputSchema(schema)
                        .build());
            }
        }
        return tools;
    }

    @Override
    public McpToolResult callTool(String name, JsonNode arguments) throws McpException {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null || arguments.isNull()
                ? objectMapper.createObjectNode() : arguments);

        JsonNode result = requestWithRecovery("tools/call", params);
        boolean isError = result.path("isError").asBoolean(false);
        String text = extractText(result.path("content"));
        return McpToolResult.builder().text(text).isError(isError).build();
    }

    /**
     * 发请求；失败时重连子进程 + 重新握手 + 重试一次。
     * 让长期运行中 MCP server 进程死亡/卡死后能自愈，无需重启应用。
     */
    private JsonNode requestWithRecovery(String method, JsonNode params) {
        try {
            ensureInitialized();
            return transport.request(method, params);
        } catch (McpException e) {
            log.warn("MCP request '{}' on '{}' failed ({}); reconnecting and retrying once",
                    method, config.getId(), e.getMessage());
            try {
                transport.reconnect();
                synchronized (this) {
                    initialized = false;
                }
                initialize();
                return transport.request(method, params);
            } catch (McpException re) {
                throw new McpException("MCP '" + config.getId()
                        + "' request '" + method + "' failed after reconnect: " + re.getMessage(), re);
            }
        }
    }

    /** 拼接 content[] 中所有 type=text 的文本；其它类型用占位描述。 */
    private String extractText(JsonNode content) {
        if (content == null || !content.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            String type = block.path("type").asText("");
            if ("text".equals(type)) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(block.path("text").asText(""));
            } else if (!type.isBlank()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("[").append(type).append(" content]");
            }
        }
        return sb.toString();
    }

    private void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
    }

    @Override
    public void close() {
        try {
            transport.close();
        } catch (Exception e) {
            log.warn("Error closing MCP transport for '{}'", config.getId(), e);
        }
    }
}
