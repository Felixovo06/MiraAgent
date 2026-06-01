package com.felix.miraagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.felix.miraagent.tools.ToolDefinition;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class OpenAICompatibleModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAICompatibleModelClient.class);

    private final RestClient restClient;
    private final ModelProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAICompatibleModelClient(RestClient restClient, ModelProperties props, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        var body = buildRequestBody(request);
        try {
            var oaiResponse = restClient.post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(OAIResponse.class);

            return convertResponse(oaiResponse);
        } catch (RestClientException e) {
            log.error("Model API call failed: {}", e.getMessage());
            throw new ModelException("Model API call failed: " + e.getMessage(), props.getName(), -1, e);
        }
    }

    @Override
    public StreamHandle streamChat(ChatRequest request, StreamCallback callback) {
        var body = buildRequestBody(request);
        body.put("stream", true);

        var aborted = new AtomicBoolean(false);
        var future = new CompletableFuture<ChatResponse>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(trimTrailingSlash(props.getBaseUrl()) + "/chat/completions"))
                        .timeout(Duration.ofMinutes(2))
                        .header("Authorization", "Bearer " + props.getApiKey())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<java.io.InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    throw new ModelException("Stream failed: HTTP " + response.statusCode() + " " + errorBody,
                            props.getName(), response.statusCode());
                }

                ChatResponse chatResponse = readSseResponse(response, callback, aborted);
                future.complete(chatResponse);
            } catch (Exception e) {
                if (!aborted.get()) {
                    log.error("Streaming failed", e);
                    callback.onDelta(StreamDelta.builder()
                            .error(e.getMessage())
                            .done(true)
                            .finishReason("error")
                            .build());
                    future.complete(ChatResponse.builder()
                            .error(new ModelException("Streaming failed: " + e.getMessage(), props.getName(), -1, e))
                            .build());
                } else {
                    future.complete(ChatResponse.builder()
                            .finishReason("aborted")
                            .assistantMessage(com.felix.miraagent.model.Message.builder()
                                    .id(UUID.randomUUID().toString())
                                    .role(MessageRole.ASSISTANT)
                                    .content("")
                                    .build())
                            .build());
                }
            }
        });

        return new StreamHandle() {
            public void abort() {
                aborted.set(true);
                worker.interrupt();
                future.cancel(true);
            }

            public boolean isComplete() {
                return future.isDone();
            }

            public ChatResponse await() {
                try {
                    return future.join();
                } catch (Exception e) {
                    if (aborted.get()) {
                        return ChatResponse.builder()
                                .finishReason("aborted")
                                .assistantMessage(com.felix.miraagent.model.Message.builder()
                                        .id(UUID.randomUUID().toString())
                                        .role(MessageRole.ASSISTANT)
                                        .content("")
                                        .build())
                                .build();
                    }
                    throw e;
                }
            }
        };
    }

    @Override
    public boolean supports(ModelCapability capability) {
        return capability == ModelCapability.TOOL_CALLING || capability == ModelCapability.STREAMING;
    }

    private Map<String, Object> buildRequestBody(ChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getName());
        body.put("temperature", request.getTemperature() != null ? request.getTemperature() : props.getTemperature());
        body.put("max_tokens", request.getMaxTokens() != null ? request.getMaxTokens() : props.getMaxTokens());
        body.put("messages", convertMessages(request.getMessages()));

        if (!request.getTools().isEmpty()) {
            body.put("tools", convertTools(request.getTools()));
            if (request.getToolChoice() != null) {
                body.put("tool_choice", request.getToolChoice());
            }
        }
        return body;
    }

    private List<Map<String, Object>> convertMessages(List<com.felix.miraagent.model.Message> messages) {
        var result = new ArrayList<Map<String, Object>>();
        for (var msg : messages) {
            var m = new LinkedHashMap<String, Object>();
            m.put("role", msg.getRole().name().toLowerCase());

            if (msg.getContent() != null) {
                m.put("content", msg.getContent());
            }

            if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                var tcs = msg.getToolCalls().stream().map(tc -> {
                    var tcMap = new LinkedHashMap<String, Object>();
                    tcMap.put("id", tc.getId());
                    tcMap.put("type", "function");
                    tcMap.put("function", Map.of("name", tc.getName(), "arguments", tc.getArguments()));
                    return tcMap;
                }).toList();
                m.put("tool_calls", tcs);
            }

            if (msg.getToolCallId() != null) {
                m.put("tool_call_id", msg.getToolCallId());
            }

            result.add(m);
        }
        return result;
    }

    private List<Map<String, Object>> convertTools(List<ToolDefinition> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var tool : tools) {
            Map<String, Object> func = new LinkedHashMap<>();
            func.put("name", tool.getName());
            func.put("description", tool.getDescription());
            if (tool.getInputSchema() != null) {
                func.put("parameters", tool.getInputSchema());
            }
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("type", "function");
            wrapper.put("function", func);
            result.add(wrapper);
        }
        return result;
    }

    private ChatResponse convertResponse(OAIResponse oaiResp) {
        if (oaiResp == null || oaiResp.getChoices() == null || oaiResp.getChoices().isEmpty()) {
            return ChatResponse.builder()
                    .error(new ModelException("Empty response from model", props.getName(), -1))
                    .build();
        }

        var choice = oaiResp.getChoices().get(0);
        var oaiMsg = choice.getMessage();

        var msgBuilder = com.felix.miraagent.model.Message.builder()
                .id(UUID.randomUUID().toString())
                .role(MessageRole.ASSISTANT)
                .content(oaiMsg.getContent());

        var responseBuilder = ChatResponse.builder()
                .finishReason(choice.getFinishReason());

        if (oaiMsg.getToolCalls() != null && !oaiMsg.getToolCalls().isEmpty()) {
            for (var oaiTc : oaiMsg.getToolCalls()) {
                var tc = ToolCall.builder()
                        .id(oaiTc.getId())
                        .name(oaiTc.getFunction().getName())
                        .arguments(oaiTc.getFunction().getArguments())
                        .build();
                msgBuilder.toolCall(tc);
                responseBuilder.toolCall(tc);
            }
        }

        if (oaiResp.getUsage() != null) {
            responseBuilder.usage(UsageInfo.builder()
                    .inputTokens(oaiResp.getUsage().getPromptTokens())
                    .outputTokens(oaiResp.getUsage().getCompletionTokens())
                    .build());
        }

        return responseBuilder.assistantMessage(msgBuilder.build()).build();
    }

    private ChatResponse readSseResponse(HttpResponse<java.io.InputStream> response, StreamCallback callback,
                                         AtomicBoolean aborted) throws Exception {
        StreamAccumulator accumulator = new StreamAccumulator();

        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            StringBuilder data = new StringBuilder();
            while (!aborted.get() && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    if (!data.isEmpty()) {
                        String payload = data.toString();
                        data.setLength(0);
                        if ("[DONE]".equals(payload.trim())) {
                            break;
                        }
                        handleStreamPayload(payload, accumulator, callback);
                    }
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).trim());
                }
            }
        }

        return accumulator.toChatResponse();
    }

    private void handleStreamPayload(String payload, StreamAccumulator accumulator, StreamCallback callback) throws Exception {
        JsonNode root = objectMapper.readTree(payload);
        JsonNode choice = root.path("choices").isArray() && !root.path("choices").isEmpty()
                ? root.path("choices").get(0) : null;
        if (choice == null || choice.isMissingNode()) {
            return;
        }

        JsonNode delta = choice.path("delta");
        JsonNode content = delta.path("content");
        if (!content.isMissingNode() && !content.isNull()) {
            String text = content.asText();
            accumulator.appendContent(text);
            callback.onDelta(StreamDelta.builder().textDelta(text).build());
        }

        JsonNode toolCalls = delta.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode tc : toolCalls) {
                accumulator.mergeToolDelta(tc);
            }
        }

        JsonNode finishReason = choice.path("finish_reason");
        if (!finishReason.isMissingNode() && !finishReason.isNull()) {
            accumulator.setFinishReason(finishReason.asText());
        }

        JsonNode usage = root.path("usage");
        if (!usage.isMissingNode() && !usage.isNull()) {
            accumulator.setUsage(usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0));
        }
    }

    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static class StreamAccumulator {
        private final StringBuilder content = new StringBuilder();
        private final Map<Integer, ToolCallBuilderState> toolStates = new TreeMap<>();
        private String finishReason;
        private UsageInfo usage;

        void appendContent(String text) {
            content.append(text);
        }

        void mergeToolDelta(JsonNode node) {
            int index = node.path("index").asInt(toolStates.size());
            ToolCallBuilderState state = toolStates.computeIfAbsent(index, ignored -> new ToolCallBuilderState());
            if (node.hasNonNull("id")) {
                state.id = node.get("id").asText();
            }
            JsonNode function = node.path("function");
            if (function.hasNonNull("name")) {
                state.name.append(function.get("name").asText());
            }
            if (function.hasNonNull("arguments")) {
                state.arguments.append(function.get("arguments").asText());
            }
        }

        void setFinishReason(String finishReason) {
            this.finishReason = finishReason;
        }

        void setUsage(int inputTokens, int outputTokens) {
            this.usage = UsageInfo.builder()
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .build();
        }

        ChatResponse toChatResponse() {
            var msgBuilder = com.felix.miraagent.model.Message.builder()
                    .id(UUID.randomUUID().toString())
                    .role(MessageRole.ASSISTANT)
                    .content(content.toString());
            var responseBuilder = ChatResponse.builder()
                    .finishReason(finishReason)
                    .usage(usage);

            for (ToolCallBuilderState state : toolStates.values()) {
                ToolCall call = ToolCall.builder()
                        .id(state.id != null ? state.id : UUID.randomUUID().toString())
                        .name(state.name.toString())
                        .arguments(state.arguments.toString())
                        .build();
                msgBuilder.toolCall(call);
                responseBuilder.toolCall(call);
            }

            return responseBuilder.assistantMessage(msgBuilder.build()).build();
        }
    }

    private static class ToolCallBuilderState {
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }

    // ---- OpenAI response DTOs ----

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class OAIResponse {
        private String id;
        private List<Choice> choices;
        private Usage usage;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class Choice {
        private OAIMessage message;
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class OAIMessage {
        private String role;
        private String content;
        @JsonProperty("tool_calls")
        private List<OAIToolCall> toolCalls;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class OAIToolCall {
        private String id;
        private String type;
        private OAIFunction function;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class OAIFunction {
        private String name;
        private String arguments;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class Usage {
        @JsonProperty("prompt_tokens")
        private int promptTokens;
        @JsonProperty("completion_tokens")
        private int completionTokens;
    }
}
