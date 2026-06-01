package com.felix.miraagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * streamable HTTP 传输：JSON-RPC 经 POST 发送，兼容直接 JSON 响应与 SSE（取 data: 帧）。
 */
public class HttpJsonRpcTransport implements JsonRpcTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpJsonRpcTransport.class);

    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final String url;
    private final String serverId;
    private final Map<String, String> headers;
    private final AtomicLong idSeq = new AtomicLong(0);

    public HttpJsonRpcTransport(String serverId, String url, ObjectMapper mapper) {
        this(serverId, url, mapper, Map.of());
    }

    public HttpJsonRpcTransport(String serverId, String url, ObjectMapper mapper, Map<String, String> headers) {
        this.serverId = serverId;
        this.url = url;
        this.mapper = mapper;
        this.headers = headers == null ? Map.of() : new LinkedHashMap<>(headers);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public JsonNode request(String method, JsonNode params) throws McpException {
        long id = idSeq.incrementAndGet();
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        JsonNode node = post(req);
        if (node.has("error") && !node.get("error").isNull()) {
            throw new McpException("MCP server '" + serverId + "' error: "
                    + node.get("error").path("message").asText());
        }
        return node.path("result");
    }

    @Override
    public void notify(String method, JsonNode params) throws McpException {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        post(req);
    }

    private JsonNode post(JsonNode body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream");
            for (Map.Entry<String, String> h : headers.entrySet()) {
                builder.header(h.getKey(), h.getValue());
            }
            HttpRequest httpReq = builder
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new McpException("MCP HTTP server '" + serverId + "' returned " + resp.statusCode());
            }
            return parseBody(resp.body());
        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("MCP HTTP request to '" + serverId + "' failed: " + e.getMessage(), e);
        }
    }

    /** 兼容直接 JSON 与 SSE：SSE 取最后一个 data: 帧。 */
    private JsonNode parseBody(String body) throws Exception {
        String trimmed = body.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return mapper.readTree(trimmed);
        }
        String last = null;
        for (String line : trimmed.split("\n")) {
            String l = line.strip();
            if (l.startsWith("data:")) {
                last = l.substring("data:".length()).strip();
            }
        }
        if (last == null) {
            throw new McpException("No JSON payload in MCP HTTP response from '" + serverId + "'");
        }
        return mapper.readTree(last);
    }

    @Override
    public void close() {
        // HttpClient 无需显式关闭
        log.debug("Closing HTTP MCP transport for '{}'", serverId);
    }
}
