package com.felix.miraagent.mcp;

import com.felix.miraagent.tools.ToolRiskLevel;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * 一个 MCP server 的连接配置（领域值对象，与具体传输实现解耦）。
 */
@Value
@Builder
public class McpServerConfig {

    /** server 唯一标识，用于命名空间前缀（如 filesystem）。 */
    String id;

    @Builder.Default
    McpTransportType transportType = McpTransportType.STDIO;

    // ---- STDIO ----
    /** 可执行命令，如 npx / uvx / python3。 */
    String command;
    @Singular
    List<String> args;
    @Singular("env")
    Map<String, String> env;

    // ---- HTTP ----
    /** streamable HTTP 端点 URL。 */
    String url;

    /** HTTP 请求自定义头（如鉴权 {@code x-api-key}），仅 HTTP 传输使用。 */
    @Singular
    Map<String, String> headers;

    /** 是否启用该 server。 */
    @Builder.Default
    boolean enabled = true;

    /** 该 server 暴露工具的统一风险等级（默认 LOW，可对写入型 server 调高）。 */
    @Builder.Default
    ToolRiskLevel toolRiskLevel = ToolRiskLevel.LOW;

    /** 工具暴露名前缀，留空则用 {@code mcp__<id>__}。 */
    String namePrefix;

    /** 实际暴露名前缀。 */
    public String effectivePrefix() {
        if (namePrefix != null && !namePrefix.isBlank()) {
            return namePrefix;
        }
        return "mcp__" + id + "__";
    }
}
