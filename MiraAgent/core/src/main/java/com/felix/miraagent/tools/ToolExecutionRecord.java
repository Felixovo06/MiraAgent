package com.felix.miraagent.tools;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class ToolExecutionRecord {
    String id;
    String runId;
    String sessionId;
    String toolCallId;
    String toolName;
    String arguments;
    ToolStatus status;
    String modelVisibleContent;
    String errorMessage;
    Instant startedAt;
    Instant finishedAt;
}
