package com.felix.miraagent.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 {@link OpenAICompatibleModelClient#sanitizeToolSequence} 修复孤儿 tool_call，避免会话永久 500。 */
class ToolSequenceSanitizeTest {

    private static Message assistantWithCall(String callId, String name) {
        return Message.builder().role(MessageRole.ASSISTANT)
                .toolCall(ToolCall.builder().id(callId).name(name).arguments("{}").build())
                .build();
    }

    private static Message toolResult(String callId) {
        return Message.builder().role(MessageRole.TOOL).toolCallId(callId).content("result").build();
    }

    private static Message user(String t) {
        return Message.builder().role(MessageRole.USER).content(t).build();
    }

    @Test
    void orphanToolCallGetsSyntheticResult() {
        // assistant 发起 tool_call 但没有结果（被截断/中断）→ 必须补一条合成结果
        var out = OpenAICompatibleModelClient.sanitizeToolSequence(
                List.of(assistantWithCall("A", "web_search"), user("ok")));

        assertEquals(3, out.size());
        assertEquals(MessageRole.ASSISTANT, out.get(0).getRole());
        assertEquals(MessageRole.TOOL, out.get(1).getRole());
        assertEquals("A", out.get(1).getToolCallId());
        assertEquals(MessageRole.USER, out.get(2).getRole());
    }

    @Test
    void pairedToolCallUnchanged() {
        // 已有真实结果时不重复补
        var out = OpenAICompatibleModelClient.sanitizeToolSequence(
                List.of(assistantWithCall("A", "web_search"), toolResult("A"), user("ok")));

        assertEquals(3, out.size());
        assertEquals("A", out.get(1).getToolCallId());
        assertEquals("result", out.get(1).getContent());
    }

    @Test
    void orphanToolMessageRemoved() {
        // tool 结果找不到对应发起 → 剔除
        var out = OpenAICompatibleModelClient.sanitizeToolSequence(
                List.of(user("hi"), toolResult("ZZZ"), user("bye")));

        assertEquals(2, out.size());
        assertTrue(out.stream().noneMatch(m -> m.getRole() == MessageRole.TOOL));
    }

    @Test
    void brokenArgumentsReplacedWithEmptyObject() {
        // arguments 是被截断的残缺 JSON → 置为 {}，并补配对结果
        var assistant = Message.builder().role(MessageRole.ASSISTANT)
                .toolCall(ToolCall.builder().id("A").name("document_write")
                        .arguments("{\"path\":\"x\",\"content\":\"# 截断…常与").build())
                .build();
        var out = OpenAICompatibleModelClient.sanitizeToolSequence(List.of(assistant, user("x")));

        assertEquals("{}", out.get(0).getToolCalls().get(0).getArguments(), "损坏 arguments 应被置为 {}");
        assertEquals(MessageRole.TOOL, out.get(1).getRole());
        assertEquals("A", out.get(1).getToolCallId());
    }

    @Test
    void validArgumentsPreserved() {
        var assistant = Message.builder().role(MessageRole.ASSISTANT)
                .toolCall(ToolCall.builder().id("A").name("s").arguments("{\"q\":\"hi\"}").build())
                .build();
        var out = OpenAICompatibleModelClient.sanitizeToolSequence(List.of(assistant, toolResult("A")));
        assertEquals("{\"q\":\"hi\"}", out.get(0).getToolCalls().get(0).getArguments(), "合法 arguments 不动");
    }

    @Test
    void multipleCallsPartialMissing() {
        var assistant = Message.builder().role(MessageRole.ASSISTANT)
                .toolCall(ToolCall.builder().id("A").name("s").arguments("{}").build())
                .toolCall(ToolCall.builder().id("B").name("s").arguments("{}").build())
                .build();
        var out = OpenAICompatibleModelClient.sanitizeToolSequence(List.of(assistant, toolResult("A")));

        var toolIds = out.stream().filter(m -> m.getRole() == MessageRole.TOOL)
                .map(Message::getToolCallId).toList();
        assertTrue(toolIds.contains("A"), "已有结果保留");
        assertTrue(toolIds.contains("B"), "缺结果的 B 补合成");
    }
}
