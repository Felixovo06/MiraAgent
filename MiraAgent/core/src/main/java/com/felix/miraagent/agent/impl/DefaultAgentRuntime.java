package com.felix.miraagent.agent.impl;

import com.felix.miraagent.agent.*;
import com.felix.miraagent.character.CharacterProfile;
import com.felix.miraagent.model.Message;
import com.felix.miraagent.model.MessageRole;
import com.felix.miraagent.session.Session;
import com.felix.miraagent.session.SessionStore;
import com.felix.miraagent.tools.impl.DefaultToolPermissionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultAgentRuntime implements AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentRuntime.class);

    private final ConversationLoop conversationLoop;
    private final SessionStore sessionStore;
    private final Map<String, InterruptSignal> activeRuns = new ConcurrentHashMap<>();
    private final ModelConfig defaultModelConfig;

    public DefaultAgentRuntime(ConversationLoop conversationLoop, SessionStore sessionStore,
                               ModelConfig defaultModelConfig) {
        this.conversationLoop = conversationLoop;
        this.sessionStore = sessionStore;
        this.defaultModelConfig = defaultModelConfig;
    }

    @Override
    public RunResult chat(ChatInput input) {
        ensureSession(input.getSessionId(), input.getUserId(), input.getCharacterId());

        List<Message> history = sessionStore.loadMessages(input.getSessionId());

        Message userMessage = Message.builder()
                .id(UUID.randomUUID().toString())
                .role(MessageRole.USER)
                .content(input.getContent())
                .build();
        sessionStore.appendMessage(input.getSessionId(), userMessage);

        List<Message> messages = new java.util.ArrayList<>(history);
        messages.add(userMessage);

        String runId = input.getRunId() != null ? input.getRunId() : UUID.randomUUID().toString();
        var signal = new InterruptSignal();
        activeRuns.put(runId, signal);

        var request = AgentRunRequest.builder()
                .runId(runId)
                .userId(input.getUserId())
                .sessionId(input.getSessionId())
                .characterProfile(resolveCharacter(input.getCharacterId()))
                .messages(messages)
                .modelConfig(defaultModelConfig)
                .toolConfig(input.getEnabledTools().isEmpty() ? null
                        : ToolConfig.builder().enabledToolNames(new java.util.HashSet<>(input.getEnabledTools())).build())
                .iterationBudget(IterationBudget.defaultBudget())
                .permissionPolicy(new DefaultToolPermissionPolicy())
                .interruptSignal(signal)
                .streamCallback(input.getStreamCallback())
                .build();

        try {
            return conversationLoop.run(request);
        } finally {
            activeRuns.remove(runId);
        }
    }

    @Override
    public RunResult runConversation(AgentRunRequest request) {
        String runId = request.getRunId();
        activeRuns.put(runId, request.getInterruptSignal());
        try {
            return conversationLoop.run(request);
        } finally {
            activeRuns.remove(runId);
        }
    }

    @Override
    public void interrupt(String runId) {
        var signal = activeRuns.get(runId);
        if (signal != null) {
            signal.interrupt();
            log.info("Interrupted run={}", runId);
        }
    }

    private void ensureSession(String sessionId, String userId, String characterId) {
        if (sessionStore.findById(sessionId).isEmpty()) {
            sessionStore.createSession(Session.builder()
                    .id(sessionId)
                    .userId(userId)
                    .characterId(characterId)
                    .source("api")
                    .build());
        }
    }

    private CharacterProfile resolveCharacter(String characterId) {
        if (characterId == null || "default".equals(characterId)) {
            return CharacterProfile.defaultProfile();
        }
        return CharacterProfile.builder().id(characterId).name(characterId).build();
    }
}
