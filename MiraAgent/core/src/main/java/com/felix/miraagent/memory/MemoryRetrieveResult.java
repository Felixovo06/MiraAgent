package com.felix.miraagent.memory;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class MemoryRetrieveResult {
    List<MemoryIndex> hits;
    String queryUsed;
}
