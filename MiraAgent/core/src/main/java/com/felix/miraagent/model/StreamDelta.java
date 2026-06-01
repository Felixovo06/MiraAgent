package com.felix.miraagent.model;

import com.felix.miraagent.tools.ToolExecutionResult;
import com.felix.miraagent.trace.TraceEvent;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class StreamDelta {
    String textDelta;
    ToolCall toolCallDelta;
    Integer toolCallIndex;
    ToolExecutionResult toolExecutionResult;
    TraceEvent traceEvent;
    String error;
    String finishReason;
    boolean done;
}
