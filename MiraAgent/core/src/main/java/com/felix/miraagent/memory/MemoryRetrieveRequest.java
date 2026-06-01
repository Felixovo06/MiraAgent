package com.felix.miraagent.memory;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class MemoryRetrieveRequest {
    String userId;
    String characterId;
    String query;
    @Builder.Default
    int limit = 10;
}
