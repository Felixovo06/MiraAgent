package com.felix.miraagent.api.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class ChatApiResponse {
    String runId;
    String sessionId;
    String traceId;
    FinalMessageDto finalMessage;
    String content;
    String status;
    List<ToolExecutionDto> toolExecutions;
    UsageDto usage;
    String error;

    @Value
    @Builder
    public static class FinalMessageDto {
        String role;
        String content;
    }

    @Value
    @Builder
    public static class ToolExecutionDto {
        String toolCallId;
        String toolName;
        String status;
        String content;
    }

    @Value
    @Builder
    public static class UsageDto {
        int inputTokens;
        int outputTokens;
    }
}
