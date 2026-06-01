package com.felix.miraagent.agent;

import com.felix.miraagent.model.StreamCallback;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class ChatInput {
    String runId;
    String userId;
    String sessionId;
    String characterId;
    String content;
    @Singular
    List<String> enabledTools;
    String model;
    @Builder.Default
    boolean stream = false;
    StreamCallback streamCallback;
    Map<String, Object> metadata;
}
