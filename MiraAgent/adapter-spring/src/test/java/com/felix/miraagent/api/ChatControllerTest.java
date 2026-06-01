package com.felix.miraagent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.felix.miraagent.agent.AgentRuntime;
import com.felix.miraagent.agent.RunResult;
import com.felix.miraagent.agent.RunStatus;
import com.felix.miraagent.api.dto.ChatApiRequest;
import com.felix.miraagent.model.Message;
import com.felix.miraagent.model.MessageRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "mira.model.api-key=test-key",
        "mira.model.base-url=http://localhost:9999"
})
@AutoConfigureMockMvc
class ChatControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    AgentRuntime agentRuntime;

    @Test
    void health() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void chat_returnsResponse() throws Exception {
        String runId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        Message finalMsg = Message.builder()
                .id(UUID.randomUUID().toString())
                .role(MessageRole.ASSISTANT)
                .content("Hello!")
                .build();

        when(agentRuntime.chat(any())).thenReturn(RunResult.builder()
                .runId(runId)
                .sessionId(sessionId)
                .status(RunStatus.SUCCESS)
                .finalMessage(finalMsg)
                .build());

        ChatApiRequest req = new ChatApiRequest();
        req.setUserId("user-1");
        req.setSessionId(sessionId);
        req.setContent("Hi");

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Hello!"))
                .andExpect(jsonPath("$.finalMessage.role").value("assistant"))
                .andExpect(jsonPath("$.finalMessage.content").value("Hello!"))
                .andExpect(jsonPath("$.traceId").value(runId))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.runId").value(runId));
    }

    @Test
    void interrupt_returnsOk() throws Exception {
        mockMvc.perform(post("/api/runs/some-run-id/interrupt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("interrupt signal sent"));
    }

    @Test
    void stream_usesStableRunIdAndSendsSseEvents() throws Exception {
        Message finalMsg = Message.builder()
                .id(UUID.randomUUID().toString())
                .role(MessageRole.ASSISTANT)
                .content("Hello stream!")
                .build();

        var captor = forClass(com.felix.miraagent.agent.ChatInput.class);
        when(agentRuntime.chat(captor.capture())).thenAnswer(invocation -> {
            var input = captor.getValue();
            input.getStreamCallback().onDelta(com.felix.miraagent.model.StreamDelta.builder()
                    .textDelta("Hello ")
                    .build());
            input.getStreamCallback().onDelta(com.felix.miraagent.model.StreamDelta.builder()
                    .textDelta("stream!")
                    .build());
            return RunResult.builder()
                    .runId(input.getRunId())
                    .sessionId(input.getSessionId())
                    .status(RunStatus.SUCCESS)
                    .finalMessage(finalMsg)
                    .build();
        });

        ChatApiRequest req = new ChatApiRequest();
        req.setUserId("user-1");
        req.setSessionId("session-stream");
        req.setContent("Hi");
        req.setStream(true);

        var mvcResult = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:start")))
                .andExpect(content().string(containsString("event:text_delta")))
                .andExpect(content().string(containsString("Hello ")))
                .andExpect(content().string(containsString("stream!")))
                .andExpect(content().string(containsString("event:done")));
    }
}
