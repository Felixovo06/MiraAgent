package com.felix.miraagent.tools.artifact;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Value
@Builder
public class ToolResultArtifact {
    String artifactId;
    String toolCallId;
    String toolName;
    String content;
    String contentType;
    long sizeBytes;
    Instant createdAt;
}
