package com.felix.miraagent.api.mapper;

import com.felix.miraagent.agent.ChatInput;
import com.felix.miraagent.agent.RunResult;
import com.felix.miraagent.api.dto.ChatApiRequest;
import com.felix.miraagent.api.dto.ChatApiResponse;
import com.felix.miraagent.model.StreamCallback;
import com.felix.miraagent.tools.ToolExecutionResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class ChatApiMapper {

    public ChatInput toInput(ChatApiRequest req) {
        return toInput(req, null, null);
    }

    public ChatInput toInput(ChatApiRequest req, String runId, StreamCallback streamCallback) {
        String sessionId = req.getSessionId() != null ? req.getSessionId() : UUID.randomUUID().toString();
        String userId = req.getUserId() != null ? req.getUserId() : "anonymous";
        return ChatInput.builder()
                .runId(runId)
                .userId(userId)
                .sessionId(sessionId)
                .characterId(req.getCharacterId())
                .content(req.getContent())
                .enabledTools(req.getEnabledTools() != null ? req.getEnabledTools() : List.of())
                .stream(req.isStream())
                .streamCallback(streamCallback)
                .build();
    }

    public ChatApiResponse toResponse(RunResult result) {
        List<ChatApiResponse.ToolExecutionDto> toolDtos = result.getToolExecutions() == null ? List.of() :
                result.getToolExecutions().stream()
                        .map(t -> ChatApiResponse.ToolExecutionDto.builder()
                                .toolCallId(t.getToolCallId())
                                .toolName(t.getToolName())
                                .status(t.getStatus().name())
                                .content(t.getModelVisibleContent())
                                .build())
                        .toList();

        ChatApiResponse.UsageDto usageDto = null;
        if (result.getUsage() != null) {
            usageDto = ChatApiResponse.UsageDto.builder()
                    .inputTokens(result.getUsage().getInputTokens())
                    .outputTokens(result.getUsage().getOutputTokens())
                    .build();
        }

        String content = result.getFinalMessage() != null ? result.getFinalMessage().getContent() : null;
        ChatApiResponse.FinalMessageDto finalMessage = null;
        if (result.getFinalMessage() != null) {
            finalMessage = ChatApiResponse.FinalMessageDto.builder()
                    .role(result.getFinalMessage().getRole().name().toLowerCase())
                    .content(content)
                    .build();
        }

        return ChatApiResponse.builder()
                .runId(result.getRunId())
                .sessionId(result.getSessionId())
                .traceId(result.getRunId())
                .finalMessage(finalMessage)
                .content(content)
                .status(result.getStatus().name())
                .toolExecutions(toolDtos)
                .usage(usageDto)
                .error(result.getError())
                .build();
    }

    public ChatApiResponse.ToolExecutionDto toToolExecutionDto(ToolExecutionResult result) {
        return ChatApiResponse.ToolExecutionDto.builder()
                .toolCallId(result.getToolCallId())
                .toolName(result.getToolName())
                .status(result.getStatus().name())
                .content(result.getModelVisibleContent())
                .build();
    }
}
