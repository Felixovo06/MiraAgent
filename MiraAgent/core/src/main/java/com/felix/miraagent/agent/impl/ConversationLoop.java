package com.felix.miraagent.agent.impl;

import com.felix.miraagent.agent.*;
import com.felix.miraagent.model.*;
import com.felix.miraagent.prompt.PromptBuildRequest;
import com.felix.miraagent.prompt.PromptBuildResult;
import com.felix.miraagent.prompt.PromptBuilder;
import com.felix.miraagent.session.SessionStore;
import com.felix.miraagent.tools.ToolDispatchContext;
import com.felix.miraagent.tools.ToolDispatcher;
import com.felix.miraagent.tools.ToolExecutionResult;
import com.felix.miraagent.tools.ToolRegistry;
import com.felix.miraagent.tools.ToolResolveContext;
import com.felix.miraagent.trace.TraceEvent;
import com.felix.miraagent.trace.TraceEventType;
import com.felix.miraagent.trace.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ConversationLoop {

    private static final Logger log = LoggerFactory.getLogger(ConversationLoop.class);

    private final ModelClient modelClient;
    private final PromptBuilder promptBuilder;
    private final ToolRegistry toolRegistry;
    private final ToolDispatcher toolDispatcher;
    private final SessionStore sessionStore;
    private final TraceStore traceStore;

    public ConversationLoop(ModelClient modelClient, PromptBuilder promptBuilder,
                            ToolRegistry toolRegistry, ToolDispatcher toolDispatcher,
                            SessionStore sessionStore, TraceStore traceStore) {
        this.modelClient = modelClient;
        this.promptBuilder = promptBuilder;
        this.toolRegistry = toolRegistry;
        this.toolDispatcher = toolDispatcher;
        this.sessionStore = sessionStore;
        this.traceStore = traceStore;
    }

    public RunResult run(AgentRunRequest request) {
        String runId = request.getRunId();
        String sessionId = request.getSessionId();
        IterationBudget budget = request.getIterationBudget();

        record(runId, sessionId, 0, TraceEventType.RUN_STARTED, Map.of("userId", request.getUserId()));

        List<Message> conversationHistory = new ArrayList<>(request.getMessages());
        List<ToolExecutionResult> allToolResults = new ArrayList<>();
        int modelCallCount = 0;
        int toolCallCount = 0;
        int stepIndex = 1;

        while (true) {
            if (request.getInterruptSignal().isInterrupted()) {
                record(runId, sessionId, stepIndex++, TraceEventType.RUN_FAILED, Map.of("reason", "interrupted"));
                return RunResult.builder().runId(runId).sessionId(sessionId)
                        .status(RunStatus.INTERRUPTED).toolExecutions(allToolResults).build();
            }

            if (modelCallCount >= budget.getMaxModelCalls()) {
                record(runId, sessionId, stepIndex++, TraceEventType.RUN_FAILED, Map.of("reason", "budget_exceeded_model_calls"));
                return RunResult.builder().runId(runId).sessionId(sessionId)
                        .status(RunStatus.BUDGET_EXCEEDED).error("Max model calls exceeded: " + budget.getMaxModelCalls())
                        .toolExecutions(allToolResults).build();
            }

            var resolveCtx = ToolResolveContext.builder()
                    .userId(request.getUserId())
                    .sessionId(sessionId)
                    .enabledToolNames(request.getToolConfig() != null
                            ? request.getToolConfig().getEnabledToolNames() : Set.of())
                    .build();

            var promptRequest = PromptBuildRequest.builder()
                    .characterProfile(request.getCharacterProfile())
                    .sessionHistory(conversationHistory)
                    .toolDefinitions(toolRegistry.listAvailable(resolveCtx))
                    .build();

            PromptBuildResult promptResult = promptBuilder.build(promptRequest);
            record(runId, sessionId, stepIndex++, TraceEventType.PROMPT_BUILT,
                    Map.of("tokenEstimate", promptResult.getTokenEstimate()));

            var chatRequest = ChatRequest.builder()
                    .messages(promptResult.getMessages())
                    .tools(toolRegistry.listAvailable(resolveCtx))
                    .temperature(request.getModelConfig() != null ? request.getModelConfig().getTemperature() : 0.7)
                    .maxTokens(request.getModelConfig() != null ? request.getModelConfig().getMaxTokens() : 2048)
                    .stream(request.getStreamCallback() != null)
                    .build();

            record(runId, sessionId, stepIndex++, TraceEventType.MODEL_REQUESTED,
                    Map.of("modelCallCount", modelCallCount));

            ChatResponse response;
            try {
                response = modelClient.chat(chatRequest);
                modelCallCount++;
            } catch (Exception e) {
                log.error("Model call failed runId={}", runId, e);
                record(runId, sessionId, stepIndex++, TraceEventType.RUN_FAILED, Map.of("error", e.getMessage()));
                return RunResult.builder().runId(runId).sessionId(sessionId)
                        .status(RunStatus.FAILED).error(e.getMessage()).toolExecutions(allToolResults).build();
            }

            record(runId, sessionId, stepIndex++, TraceEventType.MODEL_RESPONDED,
                    Map.of("finishReason", String.valueOf(response.getFinishReason())));

            if (response.hasError()) {
                record(runId, sessionId, stepIndex++, TraceEventType.RUN_FAILED,
                        Map.of("error", response.getError().getMessage()));
                return RunResult.builder().runId(runId).sessionId(sessionId)
                        .status(RunStatus.FAILED).error(response.getError().getMessage())
                        .toolExecutions(allToolResults).build();
            }

            if (response.hasToolCalls()) {
                record(runId, sessionId, stepIndex++, TraceEventType.TOOL_CALL_RECEIVED,
                        Map.of("count", response.getToolCalls().size()));

                Message assistantMsg = response.getAssistantMessage() != null
                        ? response.getAssistantMessage()
                        : Message.builder()
                                .id(UUID.randomUUID().toString())
                                .role(MessageRole.ASSISTANT)
                                .toolCalls(response.getToolCalls())
                                .build();

                sessionStore.appendMessage(sessionId, assistantMsg);
                conversationHistory.add(assistantMsg);

                if (toolCallCount + response.getToolCalls().size() > budget.getMaxToolCalls()) {
                    record(runId, sessionId, stepIndex++, TraceEventType.RUN_FAILED,
                            Map.of("reason", "budget_exceeded_tool_calls"));
                    return RunResult.builder().runId(runId).sessionId(sessionId)
                            .status(RunStatus.BUDGET_EXCEEDED).error("Max tool calls exceeded")
                            .toolExecutions(allToolResults).build();
                }

                var dispatchCtx = ToolDispatchContext.builder()
                        .runId(runId).sessionId(sessionId).userId(request.getUserId())
                        .permissionPolicy(request.getPermissionPolicy())
                        .build();

                for (ToolCall tc : response.getToolCalls()) {
                    record(runId, sessionId, stepIndex++, TraceEventType.TOOL_EXECUTION_STARTED,
                            Map.of("tool", tc.getName(), "callId", tc.getId()));
                }

                List<ToolExecutionResult> results = toolDispatcher.dispatchAll(response.getToolCalls(), dispatchCtx);
                toolCallCount += results.size();
                allToolResults.addAll(results);

                for (ToolExecutionResult result : results) {
                    if (result.getStatus() == com.felix.miraagent.tools.ToolStatus.DENIED) {
                        record(runId, sessionId, stepIndex++, TraceEventType.PERMISSION_DENIED,
                                Map.of("tool", result.getToolName(), "callId", result.getToolCallId()));
                    }
                    record(runId, sessionId, stepIndex++, TraceEventType.TOOL_EXECUTION_FINISHED,
                            Map.of("tool", result.getToolName(), "status", result.getStatus().name()));

                    Message toolMsg = Message.builder()
                            .id(UUID.randomUUID().toString())
                            .role(MessageRole.TOOL)
                            .toolCallId(result.getToolCallId())
                            .toolName(result.getToolName())
                            .content(result.getModelVisibleContent())
                            .build();
                    sessionStore.appendMessage(sessionId, toolMsg);
                    conversationHistory.add(toolMsg);
                }

                continue;
            }

            Message finalMsg = response.getAssistantMessage();
            if (finalMsg == null) {
                finalMsg = Message.builder()
                        .id(UUID.randomUUID().toString())
                        .role(MessageRole.ASSISTANT)
                        .content("")
                        .build();
            }
            sessionStore.appendMessage(sessionId, finalMsg);
            sessionStore.updateLastMessageAt(sessionId);

            record(runId, sessionId, stepIndex++, TraceEventType.FINAL_RESPONSE,
                    Map.of("contentLength", finalMsg.getContent() != null ? finalMsg.getContent().length() : 0));
            record(runId, sessionId, stepIndex++, TraceEventType.SESSION_PERSISTED, Map.of());

            return RunResult.builder()
                    .runId(runId).sessionId(sessionId)
                    .status(RunStatus.SUCCESS)
                    .finalMessage(finalMsg)
                    .toolExecutions(allToolResults)
                    .build();
        }
    }

    private void record(String runId, String sessionId, int stepIndex, TraceEventType type, Map<String, Object> payload) {
        traceStore.record(TraceEvent.builder()
                .id(UUID.randomUUID().toString())
                .runId(runId).sessionId(sessionId)
                .stepIndex(stepIndex).eventType(type)
                .payload(payload)
                .build());
    }
}
