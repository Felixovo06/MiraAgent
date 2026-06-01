package com.felix.miraagent.api.sse;

import com.felix.miraagent.api.dto.ChatApiResponse;
import com.felix.miraagent.api.mapper.ChatApiMapper;
import com.felix.miraagent.model.StreamDelta;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

@Component
public class ChatSseEmitterWriter {

    private final ChatApiMapper mapper;

    public ChatSseEmitterWriter(ChatApiMapper mapper) {
        this.mapper = mapper;
    }

    public void sendStart(SseEmitter emitter, String runId, String sessionId) throws IOException {
        emitter.send(SseEmitter.event()
                .name("start")
                .data(Map.of("runId", runId, "sessionId", sessionId)));
    }

    public void sendDone(SseEmitter emitter, ChatApiResponse response) throws IOException {
        emitter.send(SseEmitter.event()
                .name("done")
                .data(response));
    }

    public void sendError(SseEmitter emitter, String message) throws IOException {
        emitter.send(SseEmitter.event()
                .name("error")
                .data(Map.of("message", message)));
    }

    public void sendDelta(SseEmitter emitter, StreamDelta delta) {
        try {
            if (delta.getTextDelta() != null && !delta.getTextDelta().isEmpty()) {
                emitter.send(SseEmitter.event()
                        .name("text_delta")
                        .data(Map.of("text", delta.getTextDelta())));
            }
            if (delta.getToolCallDelta() != null) {
                emitter.send(SseEmitter.event()
                        .name("tool_call")
                        .data(delta.getToolCallDelta()));
            }
            if (delta.getToolExecutionResult() != null) {
                emitter.send(SseEmitter.event()
                        .name("tool_result")
                        .data(mapper.toToolExecutionDto(delta.getToolExecutionResult())));
            }
            if (delta.getTraceEvent() != null) {
                emitter.send(SseEmitter.event()
                        .name("trace")
                        .data(delta.getTraceEvent()));
            }
            if (delta.getError() != null) {
                sendError(emitter, delta.getError());
            }
        } catch (IOException e) {
            throw new IllegalStateException("SSE send failed", e);
        }
    }
}
