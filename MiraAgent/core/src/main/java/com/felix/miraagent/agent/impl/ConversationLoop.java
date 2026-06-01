package com.felix.miraagent.agent.impl;

import com.felix.miraagent.agent.*;
import com.felix.miraagent.memory.MemoryRetriever;
import com.felix.miraagent.memory.MemoryStore;
import com.felix.miraagent.model.*;
import com.felix.miraagent.prompt.PromptBuildRequest;
import com.felix.miraagent.prompt.PromptBuildResult;
import com.felix.miraagent.prompt.PromptBuilder;
import com.felix.miraagent.session.SessionStore;
import com.felix.miraagent.tools.ToolDispatchContext;
import com.felix.miraagent.tools.ToolDispatcher;
import com.felix.miraagent.tools.ToolExecutionResult;
import com.felix.miraagent.tools.ToolExecutionStore;
import com.felix.miraagent.tools.ToolRegistry;
import com.felix.miraagent.tools.ToolResolveContext;
import com.felix.miraagent.tools.artifact.ToolResultArtifact;
import com.felix.miraagent.tools.artifact.ToolResultBudget;
import com.felix.miraagent.tools.artifact.ToolResultCache;
import com.felix.miraagent.trace.TraceEvent;
import com.felix.miraagent.trace.TraceEventType;
import com.felix.miraagent.trace.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

public class ConversationLoop {

    private static final Logger log = LoggerFactory.getLogger(ConversationLoop.class);

    private final ModelClient modelClient;
    private final PromptBuilder promptBuilder;
    private final ToolRegistry toolRegistry;
    private final ToolDispatcher toolDispatcher;
    private final SessionStore sessionStore;
    private final TraceStore traceStore;
    private final ToolExecutionStore toolExecutionStore;
    private final MemoryStore memoryStore;
    private final MemoryRetriever memoryRetriever;
    private final ToolResultCache toolResultCache;

    public ConversationLoop(ModelClient modelClient, PromptBuilder promptBuilder,
                            ToolRegistry toolRegistry, ToolDispatcher toolDispatcher,
                            SessionStore sessionStore, TraceStore traceStore,
                            ToolExecutionStore toolExecutionStore) {
        this(modelClient, promptBuilder, toolRegistry, toolDispatcher, sessionStore, traceStore,
                toolExecutionStore, null, null);
    }

    public ConversationLoop(ModelClient modelClient, PromptBuilder promptBuilder,
                            ToolRegistry toolRegistry, ToolDispatcher toolDispatcher,
                            SessionStore sessionStore, TraceStore traceStore,
                            ToolExecutionStore toolExecutionStore,
                            MemoryStore memoryStore, MemoryRetriever memoryRetriever) {
        this(modelClient, promptBuilder, toolRegistry, toolDispatcher, sessionStore, traceStore,
                toolExecutionStore, memoryStore, memoryRetriever, null);
    }

    public ConversationLoop(ModelClient modelClient, PromptBuilder promptBuilder,
                            ToolRegistry toolRegistry, ToolDispatcher toolDispatcher,
                            SessionStore sessionStore, TraceStore traceStore,
                            ToolExecutionStore toolExecutionStore,
                            MemoryStore memoryStore, MemoryRetriever memoryRetriever,
                            ToolResultCache toolResultCache) {
        this.modelClient = modelClient;
        this.promptBuilder = promptBuilder;
        this.toolRegistry = toolRegistry;
        this.toolDispatcher = toolDispatcher;
        this.sessionStore = sessionStore;
        this.traceStore = traceStore;
        this.toolExecutionStore = toolExecutionStore;
        this.memoryStore = memoryStore;
        this.memoryRetriever = memoryRetriever;
        this.toolResultCache = toolResultCache;
    }

    public RunResult run(AgentRunRequest request) {
        String runId = request.getRunId();
        String sessionId = request.getSessionId();
        IterationBudget budget = request.getIterationBudget();

        emitTrace(request, runId, sessionId, 0, TraceEventType.RUN_STARTED, Map.of("userId", request.getUserId()));

        List<Message> conversationHistory = new ArrayList<>(request.getMessages());
        List<ToolExecutionResult> allToolResults = new ArrayList<>();
        int modelCallCount = 0;
        int toolCallCount = 0;
        int stepIndex = 1;
        int lastRealInputTokens = 0; // 由模型返回的真实输入 token 数，0 表示尚无数据

        while (true) {
            if (request.getInterruptSignal().isInterrupted()) {
                emitTrace(request, runId, sessionId, stepIndex++, TraceEventType.RUN_FAILED, Map.of("reason", "interrupted"));
                streamDone(request, "interrupted");
                return RunResult.builder().runId(runId).sessionId(sessionId)
                        .status(RunStatus.INTERRUPTED).toolExecutions(allToolResults).build();
            }

            if (modelCallCount >= budget.getMaxModelCalls()) {
                emitTrace(request, runId, sessionId, stepIndex++, TraceEventType.RUN_FAILED, Map.of("reason", "budget_exceeded_model_calls"));
                streamDone(request, "budget_exceeded");
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

            String userProfileSummary = "";
            String relationshipMemory = "";
            if (memoryStore != null) {
                userProfileSummary = truncate(memoryStore.readFile(request.getUserId(), "USER.md"), 500);
                String charId = request.getCharacterProfile() != null ? request.getCharacterProfile().getId() : null;
                if (charId != null) {
                    relationshipMemory = truncate(memoryStore.readFile(request.getUserId(), "characters/" + charId + "/RELATIONSHIP.md"), 500);
                }
            }

            var promptRequest = PromptBuildRequest.builder()
                    .characterProfile(request.getCharacterProfile())
                    .userProfileSummary(userProfileSummary)
                    .relationshipMemory(relationshipMemory)
                    .sessionHistory(conversationHistory)
                    .toolDefinitions(toolRegistry.listAvailable(resolveCtx))
                    .contextBudget(lastRealInputTokens)
                    .build();

            PromptBuildResult promptResult = promptBuilder.build(promptRequest);
            emitTrace(request, runId, sessionId, stepIndex++, TraceEventType.PROMPT_BUILT,
                    Map.of("tokenEstimate", promptResult.getTokenEstimate()));

            var chatRequest = ChatRequest.builder()
                    .messages(promptResult.getMessages())
                    .tools(toolRegistry.listAvailable(resolveCtx))
                    .temperature(request.getModelConfig() != null ? request.getModelConfig().getTemperature() : 0.7)
                    .maxTokens(request.getModelConfig() != null ? request.getModelConfig().getMaxTokens() : 2048)
                    .stream(request.getStreamCallback() != null)
                    .build();

            emitTrace(request, runId, sessionId, stepIndex++, TraceEventType.MODEL_REQUESTED,
                    Map.of("modelCallCount", modelCallCount));

            ChatResponse response;
            try {
                if (request.getStreamCallback() != null) {
                    response = streamModel(request, chatRequest);
                } else {
                    response = modelClient.chat(chatRequest);
                }
                modelCallCount++;
            } catch (RunInterruptedException e) {
                emitTrace(request, runId, sessionId, stepIndex++, TraceEventType.RUN_FAILED, Map.of("reason", "interrupted"));
                streamDone(request, "interrupted");
                return RunResult.builder().runId(runId).sessionId(sessionId)
                        .status(RunStatus.INTERRUPTED).toolExecutions(allToolResults).build();
            } catch (Exception e) {
                log.error("Model call failed runId={}", runId, e);
                streamError(request, e.getMessage());
                emitTrace(request, runId, sessionId, stepIndex++, TraceEventType.RUN_FAILED, Map.of("error", e.getMessage()));
                return RunResult.builder().runId(runId).sessionId(sessionId)
                        .status(RunStatus.FAILED).error(e.getMessage()).toolExecutions(allToolResults).build();
            }

            if (response.getUsage() != null && response.getUsage().getInputTokens() > 0) {
                lastRealInputTokens = response.getUsage().getInputTokens();
            }
            emitTrace(request, runId, sessionId, stepIndex++, TraceEventType.MODEL_RESPONDED,
                    Map.of("finishReason", String.valueOf(response.getFinishReason()),
                            "inputTokens", lastRealInputTokens,
                            "outputTokens", response.getUsage() != null ? response.getUsage().getOutputTokens() : 0));

            if (response.hasError()) {
                streamError(request, response.getError().getMessage());
                emitTrace(request, runId, sessionId, stepIndex++, TraceEventType.RUN_FAILED,
                        Map.of("error", response.getError().getMessage()));
                return RunResult.builder().runId(runId).sessionId(sessionId)
                        .status(RunStatus.FAILED).error(response.getError().getMessage())
                        .toolExecutions(allToolResults).build();
            }

            if (response.hasToolCalls()) {
                emitTrace(request, runId, sessionId, stepIndex++, TraceEventType.TOOL_CALL_RECEIVED,
                        Map.of("count", response.getToolCalls().size()));
                emitToolCalls(request, response.getToolCalls());

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
                    emitTrace(request, runId, sessionId, stepIndex++, TraceEventType.RUN_FAILED,
                            Map.of("reason", "budget_exceeded_tool_calls"));
                    streamDone(request, "budget_exceeded");
                    return RunResult.builder().runId(runId).sessionId(sessionId)
                            .status(RunStatus.BUDGET_EXCEEDED).error("Max tool calls exceeded")
                            .toolExecutions(allToolResults).build();
                }

                var dispatchCtx = ToolDispatchContext.builder()
                        .runId(runId).sessionId(sessionId).userId(request.getUserId())
                        .permissionPolicy(request.getPermissionPolicy())
                        .build();

                for (ToolCall tc : response.getToolCalls()) {
                    emitTrace(request, runId, sessionId, stepIndex++, TraceEventType.TOOL_EXECUTION_STARTED,
                            Map.of("tool", tc.getName(), "callId", tc.getId()));
                }

                List<ToolExecutionResult> results = toolDispatcher.dispatchAll(response.getToolCalls(), dispatchCtx);
                toolCallCount += results.size();
                allToolResults.addAll(results);

                for (int i = 0; i < results.size(); i++) {
                    ToolExecutionResult result = results.get(i);
                    ToolCall call = response.getToolCalls().get(i);
                    toolExecutionStore.record(runId, sessionId, call, result);
                    if (result.getStatus() == com.felix.miraagent.tools.ToolStatus.DENIED) {
                        emitTrace(request, runId, sessionId, stepIndex++, TraceEventType.PERMISSION_DENIED,
                                Map.of("tool", result.getToolName(), "callId", result.getToolCallId()));
                    }
                    emitTrace(request, runId, sessionId, stepIndex++, TraceEventType.TOOL_EXECUTION_FINISHED,
                            Map.of("tool", result.getToolName(), "status", result.getStatus().name()));
                    emitToolResult(request, result);

                    String modelVisibleContent = result.getModelVisibleContent();
                    if (toolResultCache != null && ToolResultBudget.shouldExternalize(modelVisibleContent)) {
                        ToolResultArtifact artifact = ToolResultArtifact.builder()
                                .artifactId(UUID.randomUUID().toString())
                                .toolCallId(result.getToolCallId())
                                .toolName(result.getToolName())
                                .content(modelVisibleContent)
                                .contentType("text/plain")
                                .sizeBytes(modelVisibleContent != null ? modelVisibleContent.getBytes().length : 0)
                                .createdAt(Instant.now())
                                .build();
                        String uri = toolResultCache.store(artifact);
                        String preview = modelVisibleContent != null && modelVisibleContent.length() > 200
                                ? modelVisibleContent.substring(0, 200) + "..."
                                : modelVisibleContent;
                        modelVisibleContent = "[artifact: " + uri + "]\n" + preview;
                    }

                    Message toolMsg = Message.builder()
                            .id(UUID.randomUUID().toString())
                            .role(MessageRole.TOOL)
                            .toolCallId(result.getToolCallId())
                            .toolName(result.getToolName())
                            .content(modelVisibleContent)
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

            emitTrace(request, runId, sessionId, stepIndex++, TraceEventType.FINAL_RESPONSE,
                    Map.of("contentLength", finalMsg.getContent() != null ? finalMsg.getContent().length() : 0));
            emitTrace(request, runId, sessionId, stepIndex++, TraceEventType.SESSION_PERSISTED, Map.of());
            streamDone(request, response.getFinishReason());

            return RunResult.builder()
                    .runId(runId).sessionId(sessionId)
                    .status(RunStatus.SUCCESS)
                    .finalMessage(finalMsg)
                    .toolExecutions(allToolResults)
                    .build();
        }
    }

    private void emitTrace(AgentRunRequest request, String runId, String sessionId, int stepIndex,
                           TraceEventType type, Map<String, Object> payload) {
        TraceEvent event = TraceEvent.builder()
                .id(UUID.randomUUID().toString())
                .runId(runId).sessionId(sessionId)
                .stepIndex(stepIndex).eventType(type)
                .payload(payload)
                .build();
        traceStore.record(event);
        if (request.getStreamCallback() != null) {
            request.getStreamCallback().onDelta(StreamDelta.builder().traceEvent(event).build());
        }
    }

    private void emitToolCalls(AgentRunRequest request, List<ToolCall> toolCalls) {
        if (request.getStreamCallback() == null) {
            return;
        }
        for (int i = 0; i < toolCalls.size(); i++) {
            request.getStreamCallback().onDelta(StreamDelta.builder()
                    .toolCallDelta(toolCalls.get(i))
                    .toolCallIndex(i)
                    .build());
        }
    }

    private void emitToolResult(AgentRunRequest request, ToolExecutionResult result) {
        if (request.getStreamCallback() != null) {
            request.getStreamCallback().onDelta(StreamDelta.builder()
                    .toolExecutionResult(result)
                    .build());
        }
    }

    private void streamError(AgentRunRequest request, String message) {
        if (request.getStreamCallback() != null) {
            request.getStreamCallback().onDelta(StreamDelta.builder()
                    .error(message)
                    .done(true)
                    .finishReason("error")
                    .build());
        }
    }

    private void streamDone(AgentRunRequest request, String finishReason) {
        if (request.getStreamCallback() != null) {
            request.getStreamCallback().onDelta(StreamDelta.builder()
                    .done(true)
                    .finishReason(finishReason != null ? finishReason : "stop")
                    .build());
        }
    }

    private ChatResponse streamModel(AgentRunRequest request, ChatRequest chatRequest) {
        StreamCallback callback = delta -> {
            if (request.getInterruptSignal().isInterrupted()) {
                throw new RunInterruptedException();
            }
            request.getStreamCallback().onDelta(delta);
        };
        var handle = modelClient.streamChat(chatRequest, callback);
        while (!handle.isComplete()) {
            if (request.getInterruptSignal().isInterrupted()) {
                handle.abort();
                throw new RunInterruptedException();
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                handle.abort();
                throw new RunInterruptedException();
            }
        }
        return handle.await();
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text != null ? text : "";
        return text.substring(0, maxChars);
    }

    private static class RunInterruptedException extends RuntimeException {
    }
}
