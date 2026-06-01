package com.felix.miraagent.api.service;

import com.felix.miraagent.agent.AgentRuntime;
import com.felix.miraagent.agent.ChatInput;
import com.felix.miraagent.agent.RunResult;
import com.felix.miraagent.api.dto.ChatApiRequest;
import com.felix.miraagent.api.dto.ChatApiResponse;
import com.felix.miraagent.api.mapper.ChatApiMapper;
import com.felix.miraagent.api.sse.ChatSseEmitterWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

@Service
public class ChatApiService {

    private static final Logger log = LoggerFactory.getLogger(ChatApiService.class);
    private static final long STREAM_TIMEOUT_MS = 120_000L;

    private final AgentRuntime agentRuntime;
    private final ChatApiMapper mapper;
    private final ChatSseEmitterWriter sseWriter;

    public ChatApiService(AgentRuntime agentRuntime, ChatApiMapper mapper, ChatSseEmitterWriter sseWriter) {
        this.agentRuntime = agentRuntime;
        this.mapper = mapper;
        this.sseWriter = sseWriter;
    }

    public ChatApiResponse chat(ChatApiRequest req) {
        RunResult result = agentRuntime.chat(mapper.toInput(req));
        return mapper.toResponse(result);
    }

    public SseEmitter stream(ChatApiRequest req) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        String runId = UUID.randomUUID().toString();
        ChatInput input = mapper.toInput(req, runId, delta -> sseWriter.sendDelta(emitter, delta));

        Thread.ofVirtual().start(() -> runStream(emitter, runId, input));
        return emitter;
    }

    private void runStream(SseEmitter emitter, String runId, ChatInput input) {
        try {
            sseWriter.sendStart(emitter, runId, input.getSessionId());

            RunResult result = agentRuntime.chat(input);
            ChatApiResponse response = mapper.toResponse(result);

            sseWriter.sendDone(emitter, response);
            emitter.complete();
        } catch (IOException e) {
            log.debug("SSE client disconnected: {}", e.getMessage());
            emitter.completeWithError(e);
        } catch (Exception e) {
            log.error("SSE stream error", e);
            try {
                sseWriter.sendError(emitter, e.getMessage());
            } catch (IOException ignored) {
            }
            emitter.completeWithError(e);
        }
    }
}
