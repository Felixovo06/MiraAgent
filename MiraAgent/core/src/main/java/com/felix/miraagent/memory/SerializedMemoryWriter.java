package com.felix.miraagent.memory;

import java.util.concurrent.CompletableFuture;

public interface SerializedMemoryWriter {
    MemoryWriteResult submit(MemoryWriteRequest request);

    /**
     * 异步提交：入队后立即返回，不阻塞调用方。用于压缩、后台复盘等非关键路径
     * （“压缩即学习”不应卡住主回复）。默认退化为同步 submit，供测试替身复用。
     */
    default CompletableFuture<MemoryWriteResult> submitAsync(MemoryWriteRequest request) {
        return CompletableFuture.completedFuture(submit(request));
    }

    MemoryWriteResult archive(String userId, String memoryId);
    void shutdown();
}
