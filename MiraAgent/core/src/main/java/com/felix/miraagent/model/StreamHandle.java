package com.felix.miraagent.model;

public interface StreamHandle {
    void abort();
    boolean isComplete();
    ChatResponse await();
}
