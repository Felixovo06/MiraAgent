package com.felix.miraagent.mcp;

import com.felix.miraagent.tools.ToolRiskLevel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 接入配置。默认 disabled——不配置 {@code mira.mcp.enabled=true} 时整个子系统不装配。
 *
 * <pre>
 * mira:
 *   mcp:
 *     enabled: true
 *     servers:
 *       - id: demo
 *         type: stdio
 *         command: python3
 *         args: [ "scripts/mcp/echo_mcp_server.py" ]
 *         tool-risk-level: low
 *       - id: remote
 *         type: http
 *         url: http://localhost:9000/mcp
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "mira.mcp")
public class McpProperties {

    private boolean enabled = false;
    private List<Server> servers = new ArrayList<>();

    @Data
    public static class Server {
        private String id;
        private McpTransportType type = McpTransportType.STDIO;
        private String command;
        private List<String> args = new ArrayList<>();
        private Map<String, String> env = new LinkedHashMap<>();
        private String url;
        /** HTTP 传输自定义请求头（如鉴权 x-api-key）。 */
        private Map<String, String> headers = new LinkedHashMap<>();
        private boolean enabled = true;
        private ToolRiskLevel toolRiskLevel = ToolRiskLevel.LOW;
        private String namePrefix;
        /** stdio 读响应超时（毫秒），防 server 卡死导致线程永久阻塞。 */
        private long requestTimeoutMillis = 30_000;
    }
}
