package com.felix.miraagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class OpenAICompatibleModelClientTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void streamChatReadsTextDeltasAndReturnsFinalResponse() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            String body = """
                    data: {"choices":[{"delta":{"role":"assistant"},"finish_reason":null}]}

                    data: {"choices":[{"delta":{"content":"Hel"},"finish_reason":null}]}

                    data: {"choices":[{"delta":{"content":"lo"},"finish_reason":null}]}

                    data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                    data: [DONE]

                    """;
            sendSse(exchange, body);
        });
        server.start();

        ModelProperties props = new ModelProperties();
        props.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        props.setApiKey("test-key");
        props.setName("test-model");
        OpenAICompatibleModelClient client = new OpenAICompatibleModelClient(
                RestClient.builder().baseUrl(props.getBaseUrl()).build(),
                props,
                new ObjectMapper());

        var deltas = new ArrayList<String>();
        ChatResponse response = client.streamChat(
                ChatRequest.builder()
                        .message(Message.builder().role(MessageRole.USER).content("hi").build())
                        .build(),
                delta -> {
                    if (delta.getTextDelta() != null) {
                        deltas.add(delta.getTextDelta());
                    }
                }).await();

        assertEquals("Hello", response.getAssistantMessage().getContent());
        assertEquals(java.util.List.of("Hel", "lo"), deltas);
        assertEquals("stop", response.getFinishReason());
    }

    @Test
    void streamChatAssemblesToolCallDeltas() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            String body = """
                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"tc1","type":"function","function":{"name":"note","arguments":"{\\"content\\""}}]},"finish_reason":null}]}

                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":":\\"hi\\"}"}}]},"finish_reason":null}]}

                    data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                    """;
            sendSse(exchange, body);
        });
        server.start();

        ModelProperties props = new ModelProperties();
        props.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        props.setApiKey("test-key");
        props.setName("test-model");
        OpenAICompatibleModelClient client = new OpenAICompatibleModelClient(
                RestClient.builder().baseUrl(props.getBaseUrl()).build(),
                props,
                new ObjectMapper());

        ChatResponse response = client.streamChat(
                ChatRequest.builder()
                        .message(Message.builder().role(MessageRole.USER).content("use a tool").build())
                        .build(),
                delta -> { }).await();

        assertTrue(response.hasToolCalls());
        assertEquals("tc1", response.getToolCalls().get(0).getId());
        assertEquals("note", response.getToolCalls().get(0).getName());
        assertEquals("{\"content\":\"hi\"}", response.getToolCalls().get(0).getArguments());
        assertEquals("tool_calls", response.getFinishReason());
    }

    private void sendSse(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
