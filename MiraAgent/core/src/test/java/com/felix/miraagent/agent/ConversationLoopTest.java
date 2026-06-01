package com.felix.miraagent.agent;

import com.felix.miraagent.agent.impl.ConversationLoop;
import com.felix.miraagent.character.CharacterProfile;
import com.felix.miraagent.fake.FakeModelClient;
import com.felix.miraagent.model.Message;
import com.felix.miraagent.model.MessageRole;
import com.felix.miraagent.model.StreamDelta;
import com.felix.miraagent.model.ToolCall;
import com.felix.miraagent.prompt.impl.DefaultPromptBuilder;
import com.felix.miraagent.session.impl.InMemorySessionStore;
import com.felix.miraagent.tools.ToolStatus;
import com.felix.miraagent.tools.builtin.BuiltinTools;
import com.felix.miraagent.tools.impl.DefaultToolDispatcher;
import com.felix.miraagent.tools.impl.DefaultToolPermissionPolicy;
import com.felix.miraagent.tools.impl.InMemoryToolExecutionStore;
import com.felix.miraagent.tools.impl.InMemoryToolRegistry;
import com.felix.miraagent.trace.TraceEventType;
import com.felix.miraagent.trace.impl.InMemoryTraceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class ConversationLoopTest {

    private FakeModelClient fakeModel;
    private InMemorySessionStore sessionStore;
    private InMemoryTraceStore traceStore;
    private InMemoryToolExecutionStore toolExecutionStore;
    private InMemoryToolRegistry toolRegistry;
    private ConversationLoop loop;

    @BeforeEach
    void setUp() {
        fakeModel = new FakeModelClient();
        sessionStore = new InMemorySessionStore();
        traceStore = new InMemoryTraceStore();
        toolExecutionStore = new InMemoryToolExecutionStore();
        toolRegistry = new InMemoryToolRegistry();
        BuiltinTools.registerAll(toolRegistry);

        loop = new ConversationLoop(
                fakeModel,
                new DefaultPromptBuilder(),
                toolRegistry,
                new DefaultToolDispatcher(toolRegistry),
                sessionStore,
                traceStore,
                toolExecutionStore
        );
    }

    private AgentRunRequest buildRequest(String sessionId, List<Message> messages) {
        return AgentRunRequest.builder()
                .runId(UUID.randomUUID().toString())
                .userId("u1")
                .sessionId(sessionId)
                .characterProfile(CharacterProfile.defaultProfile())
                .messages(messages)
                .modelConfig(ModelConfig.builder().modelName("fake").build())
                .iterationBudget(IterationBudget.defaultBudget())
                .permissionPolicy(new DefaultToolPermissionPolicy())
                .build();
    }

    private Message userMessage(String content) {
        return Message.builder().id(UUID.randomUUID().toString())
                .role(MessageRole.USER).content(content).build();
    }

    @Test
    void shouldReturnFinalResponseWhenModelHasNoToolCall() {
        fakeModel.thenReply("Hello! How can I help you?");
        var request = buildRequest("s1", List.of(userMessage("Hi")));
        var result = loop.run(request);

        assertEquals(RunStatus.SUCCESS, result.getStatus());
        assertNotNull(result.getFinalMessage());
        assertEquals("Hello! How can I help you?", result.getFinalMessage().getContent());
        assertTrue(result.getToolExecutions().isEmpty());
    }

    @Test
    void shouldDispatchToolAndContinueLoop() {
        fakeModel.thenCallTool("tc1", "note", "{\"content\":\"important note\"}")
                 .thenReply("I saved that note for you.");

        var request = buildRequest("s2", List.of(userMessage("Save a note")));
        var result = loop.run(request);

        assertEquals(RunStatus.SUCCESS, result.getStatus());
        assertEquals(1, result.getToolExecutions().size());
        assertEquals(ToolStatus.SUCCESS, result.getToolExecutions().get(0).getStatus());
        assertEquals("I saved that note for you.", result.getFinalMessage().getContent());
        assertEquals(1, toolExecutionStore.findByRunId(request.getRunId()).size());
    }

    @Test
    void shouldPreserveToolResultOrderWhenExecutingMultipleTools() {
        var tc1 = ToolCall.builder().id("tc1").name("note").arguments("{\"content\":\"first\"}").build();
        var tc2 = ToolCall.builder().id("tc2").name("todo").arguments("{\"task\":\"second\"}").build();
        var tc3 = ToolCall.builder().id("tc3").name("note").arguments("{\"content\":\"third\"}").build();

        fakeModel.thenCallTools(tc1, tc2, tc3)
                 .thenReply("Done! All three actions completed.");

        var request = buildRequest("s3", List.of(userMessage("Do three things")));
        var result = loop.run(request);

        assertEquals(RunStatus.SUCCESS, result.getStatus());
        assertEquals(3, result.getToolExecutions().size());
        assertEquals("tc1", result.getToolExecutions().get(0).getToolCallId());
        assertEquals("tc2", result.getToolExecutions().get(1).getToolCallId());
        assertEquals("tc3", result.getToolExecutions().get(2).getToolCallId());
    }

    @Test
    void shouldReturnErrorResultWhenToolFails() {
        fakeModel.thenCallTool("tc1", "note", "{}")
                 .thenReply("I see the note tool had an issue.");

        var request = buildRequest("s4", List.of(userMessage("Save empty note")));
        var result = loop.run(request);

        assertEquals(RunStatus.SUCCESS, result.getStatus());
        assertEquals(1, result.getToolExecutions().size());
        assertEquals(ToolStatus.ERROR, result.getToolExecutions().get(0).getStatus());
        assertEquals("I see the note tool had an issue.", result.getFinalMessage().getContent());
    }

    @Test
    void shouldReturnBudgetExceededWhenMaxModelCallsReached() {
        var tinyBudget = IterationBudget.builder().maxModelCalls(2).maxToolCalls(10).build();

        fakeModel.thenCallTool("tc1", "note", "{\"content\":\"a\"}")
                 .thenCallTool("tc2", "note", "{\"content\":\"b\"}")
                 .thenReply("done");

        var request = AgentRunRequest.builder()
                .runId(UUID.randomUUID().toString())
                .userId("u1").sessionId("s5")
                .characterProfile(CharacterProfile.defaultProfile())
                .messages(List.of(userMessage("loop")))
                .modelConfig(ModelConfig.builder().modelName("fake").build())
                .iterationBudget(tinyBudget)
                .permissionPolicy(new DefaultToolPermissionPolicy())
                .build();

        var result = loop.run(request);
        assertEquals(RunStatus.BUDGET_EXCEEDED, result.getStatus());
    }

    @Test
    void shouldRecordTraceEvents() {
        fakeModel.thenReply("Hello!");
        var request = buildRequest("s6", List.of(userMessage("Hi")));
        loop.run(request);

        var events = traceStore.findByRunId(request.getRunId());
        assertFalse(events.isEmpty());
        assertTrue(events.stream().anyMatch(e -> e.getEventType() == TraceEventType.RUN_STARTED));
        assertTrue(events.stream().anyMatch(e -> e.getEventType() == TraceEventType.FINAL_RESPONSE));
    }

    @Test
    void shouldPersistMessagesToSessionStore() {
        fakeModel.thenReply("Got it!");
        var session = "s7";
        sessionStore.createSession(com.felix.miraagent.session.Session.builder()
                .id(session).userId("u1").build());

        var request = buildRequest(session, List.of(userMessage("Hello")));
        loop.run(request);

        var messages = sessionStore.loadMessages(session);
        assertTrue(messages.stream().anyMatch(m -> m.getRole() == MessageRole.ASSISTANT));
    }

    @Test
    void shouldEmitStreamingDeltasTraceAndToolResults() {
        fakeModel.thenCallTool("tc1", "note", "{\"content\":\"stream note\"}")
                .thenReply("Done streaming.");

        var deltas = new CopyOnWriteArrayList<StreamDelta>();
        var request = AgentRunRequest.builder()
                .runId(UUID.randomUUID().toString())
                .userId("u1")
                .sessionId("s8")
                .characterProfile(CharacterProfile.defaultProfile())
                .message(userMessage("stream please"))
                .modelConfig(ModelConfig.builder().modelName("fake").build())
                .iterationBudget(IterationBudget.defaultBudget())
                .permissionPolicy(new DefaultToolPermissionPolicy())
                .streamCallback(deltas::add)
                .build();

        var result = loop.run(request);

        assertEquals(RunStatus.SUCCESS, result.getStatus());
        assertTrue(deltas.stream().anyMatch(d -> d.getTextDelta() != null && d.getTextDelta().contains("Done streaming.")));
        assertTrue(deltas.stream().anyMatch(d -> d.getToolCallDelta() != null && d.getToolCallDelta().getName().equals("note")));
        assertTrue(deltas.stream().anyMatch(d -> d.getToolExecutionResult() != null));
        assertTrue(deltas.stream().anyMatch(d -> d.getTraceEvent() != null));
        assertTrue(deltas.stream().anyMatch(StreamDelta::isDone));
    }
}
