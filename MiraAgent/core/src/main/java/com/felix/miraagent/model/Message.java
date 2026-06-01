package com.felix.miraagent.model;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.time.Instant;
import java.util.List;

@Value
@Builder(toBuilder = true)
public class Message {
    String id;
    MessageRole role;
    String content;
    @Singular
    List<ToolCall> toolCalls;
    String toolCallId;
    String toolName;
    /**
     * 多模态图片附件（data URL，如 {@code data:image/png;base64,...}），仅用于发往模型的请求，
     * 不持久化、不进历史——只在上传那一轮随消息发给模型。
     */
    @Singular
    List<String> imageDataUrls;
    @Builder.Default
    Instant createdAt = Instant.now();
}
