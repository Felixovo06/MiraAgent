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

    @Test
    void streamChatParsesUsageFromFinalEmptyChoicesChunk() throws IOException {
        // include_usage 时末尾 chunk choices 为空但携带 usage，不能因空 choices 提前 return
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            String body = """
                    data: {"choices":[{"delta":{"content":"Hi"},"finish_reason":null}]}

                    data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                    data: {"choices":[],"usage":{"prompt_tokens":123,"completion_tokens":45}}

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
                RestClient.builder().baseUrl(props.getBaseUrl()).build(), props, new ObjectMapper());

        ChatResponse response = client.streamChat(
                ChatRequest.builder()
                        .message(Message.builder().role(MessageRole.USER).content("hi").build())
                        .build(),
                delta -> { }).await();

        assertEquals("Hi", response.getAssistantMessage().getContent());
        assertNotNull(response.getUsage(), "末尾 usage chunk 应被解析");
        assertEquals(123, response.getUsage().getInputTokens());
        assertEquals(45, response.getUsage().getOutputTokens());
    }

    @Test
    void streamChatSendsMultimodalContentWhenMessageHasImages() throws IOException {
        var captured = new java.util.concurrent.atomic.AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sendSse(exchange, """
                    data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}

                    data: [DONE]

                    """);
        });
        server.start();

        ModelProperties props = new ModelProperties();
        props.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        props.setApiKey("test-key");
        props.setName("test-model");
        OpenAICompatibleModelClient client = new OpenAICompatibleModelClient(
                RestClient.builder().baseUrl(props.getBaseUrl()).build(), props, new ObjectMapper());

        client.streamChat(
                ChatRequest.builder()
                        .message(Message.builder()
                                .role(MessageRole.USER)
                                .content("这是什么")
                                .imageDataUrl("data:image/png;base64,AAAA")
                                .build())
                        .build(),
                delta -> { }).await();

        String body = captured.get();
        assertNotNull(body);
        assertTrue(body.contains("\"image_url\""), "应发出多模态 image_url 部件");
        assertTrue(body.contains("data:image/png;base64,AAAA"), "应内联图片 data URL");
        assertTrue(body.contains("\"这是什么\""), "文字部件应保留");
    }

    private void sendSse(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
