package com.felix.miraagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HttpJsonRpcTransportTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void requestSendsConfiguredCustomHeaders() throws IOException {
        // Exa 等远程 MCP 用 x-api-key 鉴权：headers 必须随每次 POST 发出
        AtomicReference<String> seenApiKey = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/mcp", exchange -> {
            seenApiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            byte[] resp = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();

        String url = "http://localhost:" + server.getAddress().getPort() + "/mcp";
        HttpJsonRpcTransport transport = new HttpJsonRpcTransport(
                "exa", url, new ObjectMapper(), Map.of("x-api-key", "secret-key"));

        JsonNode result = transport.request("tools/list", null);

        assertEquals("secret-key", seenApiKey.get(), "自定义 x-api-key 头应随请求发出");
        assertEquals(true, result.path("ok").asBoolean());
    }

    @Test
    void requestWithoutHeadersSendsNone() throws IOException {
        AtomicReference<String> seenApiKey = new AtomicReference<>("present");
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/mcp", exchange -> {
            seenApiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            byte[] resp = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();

        String url = "http://localhost:" + server.getAddress().getPort() + "/mcp";
        HttpJsonRpcTransport transport = new HttpJsonRpcTransport("plain", url, new ObjectMapper());

        transport.request("tools/list", null);

        assertNull(seenApiKey.get(), "未配置 headers 时不应发出 x-api-key");
    }
}
