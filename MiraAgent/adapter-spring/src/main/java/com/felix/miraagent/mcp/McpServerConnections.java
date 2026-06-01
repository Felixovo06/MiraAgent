package com.felix.miraagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.felix.miraagent.tools.ToolRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动时按配置建立各 MCP server 连接，发现工具并桥接进统一 {@link ToolRegistry}。
 * 持有 client 引用，容器关闭时统一释放（子进程销毁）。
 * <p>构造期完成注册——因依赖 ToolRegistry bean，Spring 保证 registry 先建好，
 * 且早于任何对话请求，故 ConversationLoop（共享同一 registry 实例）即可见到 MCP 工具。
 */
public class McpServerConnections {

    private static final Logger log = LoggerFactory.getLogger(McpServerConnections.class);

    private final List<McpClient> clients = new ArrayList<>();
    private final List<McpToolRegistryBridge.Registered> registered;

    public McpServerConnections(McpProperties properties, ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        for (McpProperties.Server s : properties.getServers()) {
            if (!s.isEnabled()) {
                log.info("MCP server '{}' disabled in config, skipping connect", s.getId());
                continue;
            }
            try {
                clients.add(buildClient(s, objectMapper));
            } catch (Exception e) {
                log.warn("Failed to build MCP client '{}': {}", s.getId(), e.getMessage());
            }
        }
        this.registered = new McpToolRegistryBridge().registerAll(clients, toolRegistry);
        log.info("MCP bridge registered {} tool(s) from {} server(s)", registered.size(), clients.size());
    }

    private McpClient buildClient(McpProperties.Server s, ObjectMapper objectMapper) {
        JsonRpcTransport transport = switch (s.getType()) {
            case STDIO -> new StdioJsonRpcTransport(s.getId(), s.getCommand(), s.getArgs(), s.getEnv(),
                    objectMapper, s.getRequestTimeoutMillis());
            case HTTP -> new HttpJsonRpcTransport(s.getId(), s.getUrl(), objectMapper, s.getHeaders());
        };
        McpServerConfig config = McpServerConfig.builder()
                .id(s.getId())
                .transportType(s.getType())
                .command(s.getCommand())
                .args(s.getArgs())
                .url(s.getUrl())
                .headers(s.getHeaders())
                .enabled(s.isEnabled())
                .toolRiskLevel(s.getToolRiskLevel())
                .namePrefix(s.getNamePrefix())
                .build();
        return new DefaultMcpClient(config, transport, objectMapper);
    }

    public List<McpToolRegistryBridge.Registered> getRegistered() {
        return registered;
    }

    @PreDestroy
    public void shutdown() {
        for (McpClient client : clients) {
            client.close();
        }
        log.info("Closed {} MCP server connection(s)", clients.size());
    }
}
