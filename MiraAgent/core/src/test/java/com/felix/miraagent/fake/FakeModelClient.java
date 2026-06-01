package com.felix.miraagent.fake;

import com.felix.miraagent.model.*;

import java.util.LinkedList;
import java.util.Queue;
import java.util.UUID;

public class FakeModelClient implements ModelClient {

    private final Queue<ChatResponse> responseQueue = new LinkedList<>();

    public FakeModelClient thenReply(String content) {
        responseQueue.add(ChatResponse.builder()
                .assistantMessage(Message.builder()
                        .id(UUID.randomUUID().toString())
                        .role(MessageRole.ASSISTANT)
                        .content(content)
                        .build())
                .finishReason("stop")
                .build());
        return this;
    }

    public FakeModelClient thenCallTool(String toolCallId, String toolName, String arguments) {
        ToolCall toolCall = ToolCall.builder()
                .id(toolCallId)
                .name(toolName)
                .arguments(arguments)
                .build();
        responseQueue.add(ChatResponse.builder()
                .assistantMessage(Message.builder()
                        .id(UUID.randomUUID().toString())
                        .role(MessageRole.ASSISTANT)
                        .toolCall(toolCall)
                        .build())
                .toolCall(toolCall)
                .finishReason("tool_calls")
                .build());
        return this;
    }

    public FakeModelClient thenCallTools(ToolCall... toolCalls) {
        var msgBuilder = Message.builder()
                .id(UUID.randomUUID().toString())
                .role(MessageRole.ASSISTANT);
        for (ToolCall tc : toolCalls) msgBuilder.toolCall(tc);

        var responseBuilder = ChatResponse.builder().finishReason("tool_calls");
        for (ToolCall tc : toolCalls) responseBuilder.toolCall(tc);
        responseBuilder.assistantMessage(msgBuilder.build());

        responseQueue.add(responseBuilder.build());
        return this;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        if (responseQueue.isEmpty()) {
            throw new IllegalStateException("FakeModelClient has no more responses queued");
        }
        return responseQueue.poll();
    }

    @Override
    public StreamHandle streamChat(ChatRequest request, StreamCallback callback) {
        var response = chat(request);
        if (response.getAssistantMessage() != null && response.getAssistantMessage().getContent() != null) {
            callback.onDelta(StreamDelta.builder().textDelta(response.getAssistantMessage().getContent()).build());
        }
        callback.onDelta(StreamDelta.builder().done(true).finishReason("stop").build());
        return new StreamHandle() {
            public void abort() {}
            public boolean isComplete() { return true; }
            public ChatResponse await() { return response; }
        };
    }

    @Override
    public boolean supports(ModelCapability capability) {
        return true;
    }
}
