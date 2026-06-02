package com.felix.miraagent.prompt;

import com.felix.miraagent.character.CharacterProfile;
import com.felix.miraagent.model.Message;
import com.felix.miraagent.model.MessageRole;
import com.felix.miraagent.prompt.impl.DefaultPromptBuilder;
import com.felix.miraagent.style.StyleConstraint;
import com.felix.miraagent.tools.ToolDefinition;
import com.felix.miraagent.tools.ToolRiskLevel;
import com.felix.miraagent.tools.builtin.BuiltinTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PromptBuilderTest {

    private PromptBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new DefaultPromptBuilder();
    }

    @Test
    void differentCharacterProfilesProduceDifferentSystemPrompt() {
        var mira = CharacterProfile.builder().id("mira").name("Mira").personality("Warm and caring").build();
        var kai = CharacterProfile.builder().id("kai").name("Kai").personality("Sharp and analytical").build();

        var requestMira = PromptBuildRequest.builder().characterProfile(mira).build();
        var requestKai = PromptBuildRequest.builder().characterProfile(kai).build();

        var resultMira = builder.build(requestMira);
        var resultKai = builder.build(requestKai);

        assertNotEquals(resultMira.getStableSystemPrompt(), resultKai.getStableSystemPrompt());
        assertTrue(resultMira.getStableSystemPrompt().contains("Mira"));
        assertTrue(resultKai.getStableSystemPrompt().contains("Kai"));
    }

    @Test
    void toolDefinitionsChangeToolSection() {
        var noTools = PromptBuildRequest.builder().build();
        var withTools = PromptBuildRequest.builder()
                .toolDefinition(BuiltinTools.noteDefinition())
                .toolDefinition(BuiltinTools.todoDefinition())
                .build();

        var resultNoTools = builder.build(noTools);
        var resultWithTools = builder.build(withTools);

        assertTrue(resultWithTools.getStableSystemPrompt().contains("note"));
        assertFalse(resultNoTools.getStableSystemPrompt().contains("note"));
    }

    @Test
    void temporaryInstructionsDoNotAffectStablePrompt() {
        var request = PromptBuildRequest.builder()
                .characterProfile(CharacterProfile.defaultProfile())
                .temporaryInstructions("TEMP: focus on brevity this turn")
                .build();

        var result = builder.build(request);

        assertFalse(result.getStableSystemPrompt().contains("TEMP:"),
                "temporary instructions must not appear in stableSystemPrompt");
        assertTrue(result.getEphemeralPrompt().contains("TEMP:"));
    }

    @Test
    void sessionHistoryIsIncludedInMessages() {
        var history = List.of(
                Message.builder().id(UUID.randomUUID().toString()).role(MessageRole.USER).content("hello").build(),
                Message.builder().id(UUID.randomUUID().toString()).role(MessageRole.ASSISTANT).content("hi").build()
        );
        var request = PromptBuildRequest.builder()
                .sessionHistoryItem(history.get(0))
                .sessionHistoryItem(history.get(1))
                .build();

        var result = builder.build(request);
        var messages = result.getMessages();

        assertEquals(MessageRole.USER, messages.get(messages.size() - 2).getRole());
        assertEquals(MessageRole.ASSISTANT, messages.get(messages.size() - 1).getRole());
    }

    @Test
    void firstMessageIsSystemWhenCharacterPresent() {
        var request = PromptBuildRequest.builder()
                .characterProfile(CharacterProfile.defaultProfile())
                .build();
        var result = builder.build(request);
        assertFalse(result.getMessages().isEmpty());
        assertEquals(MessageRole.SYSTEM, result.getMessages().get(0).getRole());
    }

    @Test
    void globalStyleConstraintInjectedBeforeCharacter() {
        var style = StyleConstraint.builder()
                .worldSetting("STYLE_WORLD_MARK")
                .tone("统一语气")
                .build();
        var styledBuilder = new DefaultPromptBuilder(() -> Optional.of(style));

        var request = PromptBuildRequest.builder()
                .characterProfile(CharacterProfile.builder().id("mira").name("MIRA_MARK").build())
                .build();
        var prompt = styledBuilder.build(request).getStableSystemPrompt();

        assertTrue(prompt.contains("STYLE_WORLD_MARK"), "世界设定应注入 stableSystemPrompt");
        assertTrue(prompt.indexOf("STYLE_WORLD_MARK") < prompt.indexOf("MIRA_MARK"),
                "全局风格约束应位于角色 section 之前");
    }

    @Test
    void noStyleProviderLeavesPromptUnchanged() {
        // 无 provider（默认构造）时不应出现风格约束相关内容
        var request = PromptBuildRequest.builder()
                .characterProfile(CharacterProfile.defaultProfile())
                .build();
        var prompt = builder.build(request).getStableSystemPrompt();
        assertFalse(prompt.contains("# 世界设定"));
    }

    @Test
    void messageSplitProtocolAlwaysInjected() {
        var prompt = builder.build(PromptBuildRequest.builder().build()).getStableSystemPrompt();
        assertTrue(prompt.contains("[[break]]"), "消息分隔协议应固定注入 stableSystemPrompt");
    }

    @Test
    void userImageDataUrlsSurviveEphemeralRewrite() {
        // 回归：ephemeral 重建最新 USER 消息时，imageDataUrls 不能丢
        var userMsg = Message.builder()
                .id(UUID.randomUUID().toString())
                .role(MessageRole.USER)
                .content("头像好看吗")
                .imageDataUrl("data:image/png;base64,AAAA")
                .build();
        var request = PromptBuildRequest.builder()
                .sessionHistoryItem(userMsg)
                .temporaryInstructions("TEMP")  // 触发 ephemeral，走重建分支
                .build();

        var messages = builder.build(request).getMessages();
        var lastUser = messages.get(messages.size() - 1);

        assertEquals(MessageRole.USER, lastUser.getRole());
        assertEquals(List.of("data:image/png;base64,AAAA"), lastUser.getImageDataUrls());
        assertTrue(lastUser.getContent().contains("TEMP"), "ephemeral 指令应已并入 content");
    }
}
