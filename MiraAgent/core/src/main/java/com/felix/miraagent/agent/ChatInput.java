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
    /** 多模态图片附件（data URL）；仅本轮随用户消息发给模型，不持久化。 */
    @Singular
    List<String> imageDataUrls;
    String model;
    @Builder.Default
    boolean stream = false;
    StreamCallback streamCallback;
    Map<String, Object> metadata;
}
