package com.felix.miraagent.prompt.impl;

import com.felix.miraagent.model.Message;
import com.felix.miraagent.model.MessageRole;
import com.felix.miraagent.prompt.PromptBuildRequest;
import com.felix.miraagent.prompt.PromptBuildResult;
import com.felix.miraagent.prompt.PromptBuilder;
import com.felix.miraagent.style.StyleConstraintProvider;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class DefaultPromptBuilder implements PromptBuilder {

    private final CharacterPromptComposer characterComposer;
    private final ToolSchemaInjector toolInjector;
    private final StyleConstraintComposer styleComposer;
    private final StyleConstraintProvider styleConstraintProvider;

    private static final String MESSAGE_SPLIT_PROTOCOL = """
            # 消息分隔
            像真人聊天那样自然分条，别把回复挤成一大段：
            - 把不同的话分成多条短消息发出；想明确分条时写 [[break]]。
            - 每条尽量简短，常常一句一条最自然。
            - 不要把一句完整的话从中间拆开，也不要每个标点都拆。
            - 要列点/清单（步骤、押题、要点）时，整组放进同一条消息里逐行列出，条目之间不要写 [[break]]，别拆成一堆气泡。

            示例：
            没什么。[[break]]你怎么洗这么快。""";

    public DefaultPromptBuilder() {
        this(null);
    }

    public DefaultPromptBuilder(StyleConstraintProvider styleConstraintProvider) {
        this.characterComposer = new CharacterPromptComposer();
        this.toolInjector = new ToolSchemaInjector();
        this.styleComposer = new StyleConstraintComposer();
        this.styleConstraintProvider = styleConstraintProvider;
    }

    @Override
    public PromptBuildResult build(PromptBuildRequest request) {
        String stableSystemPrompt = buildStableSystemPrompt(request);
        String ephemeralPrompt = buildEphemeralPrompt(request);

        List<Message> messages = buildMessages(stableSystemPrompt, ephemeralPrompt, request);

        return PromptBuildResult.builder()
                .stableSystemPrompt(stableSystemPrompt)
                .ephemeralPrompt(ephemeralPrompt)
                .messages(messages)
                .tokenEstimate(estimateTokens(stableSystemPrompt, ephemeralPrompt, request))
                .build();
    }

    private String buildStableSystemPrompt(PromptBuildRequest request) {
        var parts = new ArrayList<String>();

        // 全局风格约束(世界设定+回复风格)：最稳定、凌驾于单个角色之上，置于最前以利 prefix caching
        if (styleConstraintProvider != null) {
            String styleSection = styleComposer.compose(styleConstraintProvider.get().orElse(null));
            if (hasText(styleSection)) {
                parts.add(styleSection);
            }
        }

        String characterSection = characterComposer.compose(request.getCharacterProfile());
        if (!characterSection.isBlank()) {
            parts.add(characterSection);
        }

        // 消息分隔协议：固定注入，让模型可主动把回复拆成多条连续消息
        parts.add(MESSAGE_SPLIT_PROTOCOL);

        if (hasText(request.getUserProfileSummary())) {
            parts.add("# User Profile\n\n" + request.getUserProfileSummary());
        }

        if (hasText(request.getRelationshipMemory())) {
            parts.add("# Relationship\n\n" + request.getRelationshipMemory());
        }

        if (!request.getRetrievedMemories().isEmpty()) {
            parts.add("# Relevant Memories\n\n" + String.join("\n", request.getRetrievedMemories()));
        }

        if (hasText(request.getSkillIndex())) {
            parts.add("# Skills\n\n" + request.getSkillIndex());
        }

        String toolSection = toolInjector.inject(request.getToolDefinitions());
        if (!toolSection.isBlank()) {
            parts.add(toolSection);
        }

        return String.join("\n\n---\n\n", parts);
    }

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm (EEE)", Locale.CHINESE);

    private String buildEphemeralPrompt(PromptBuildRequest request) {
        var parts = new ArrayList<String>();
        parts.add("当前时间：" + ZonedDateTime.now().format(TIME_FMT));
        if (hasText(request.getTemporaryInstructions())) {
            parts.add(request.getTemporaryInstructions());
        }
        return String.join("\n", parts);
    }

    private List<Message> buildMessages(String stableSystemPrompt, String ephemeralPrompt, PromptBuildRequest request) {
        var messages = new ArrayList<Message>();

        // SYSTEM 只含 stableSystemPrompt，保证 prefix caching 生效
        if (hasText(stableSystemPrompt)) {
            messages.add(Message.builder()
                    .id(UUID.randomUUID().toString())
                    .role(MessageRole.SYSTEM)
                    .content(stableSystemPrompt)
                    .build());
        }

        if (!hasText(ephemeralPrompt)) {
            messages.addAll(request.getSessionHistory());
        } else {
            // ephemeral 追加到最新 USER 消息，避免污染稳定 SYSTEM 前缀
            List<Message> history = request.getSessionHistory();
            int lastUserIdx = -1;
            for (int i = history.size() - 1; i >= 0; i--) {
                if (history.get(i).getRole() == MessageRole.USER) {
                    lastUserIdx = i;
                    break;
                }
            }
            for (int i = 0; i < history.size(); i++) {
                if (i == lastUserIdx) {
                    Message orig = history.get(i);
                    String combined = hasText(orig.getContent())
                            ? orig.getContent() + "\n\n[Instructions]\n" + ephemeralPrompt
                            : ephemeralPrompt;
                    // 用 toBuilder 保留 imageDataUrls 等字段，只覆盖 content
                    messages.add(orig.toBuilder()
                            .content(combined)
                            .build());
                } else {
                    messages.add(history.get(i));
                }
            }
        }

        return messages;
    }

    // 只估算本轮新增内容（system prompt + ephemeral），不对全部历史做 chars/4
    // 真实上下文总量由 ConversationLoop 从 UsageInfo.inputTokens 获取
    private int estimateTokens(String stable, String ephemeral, PromptBuildRequest request) {
        int estimate = estimateChars(stable) + estimateChars(ephemeral);
        // 仅估算最新一条消息，用于拦截单个超长工具结果顶爆上下文窗口
        List<Message> history = request.getSessionHistory();
        if (!history.isEmpty()) {
            estimate += estimateChars(history.getLast().getContent());
        }
        return estimate;
    }

    private int estimateChars(String text) {
        if (text == null || text.isEmpty()) return 0;
        int tokens = 0;
        for (char c : text.toCharArray()) {
            // CJK 字符约 0.6 token，ASCII 约 0.25 token
            tokens += (c > 0x2E7F) ? 6 : 2;
        }
        return tokens / 10;
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
