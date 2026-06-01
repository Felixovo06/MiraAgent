package com.felix.miraagent.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.felix.miraagent.tools.ToolDefinition;
import com.felix.miraagent.tools.ToolExecutionResult;
import com.felix.miraagent.tools.ToolHandler;
import com.felix.miraagent.tools.ToolRiskLevel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 网页提取工具（MEDIUM 风险，外部网络只读）。GET 一个 http/https URL，
 * 去除 HTML 标签得到可读文本。长结果由 ConversationLoop 中央外置为 artifact。
 */
public class WebFetchToolHandler implements ToolHandler {

    public static final String NAME = "web_fetch";
    private static final int MAX_CHARS = 20_000;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public static ToolDefinition definition() {
        return ToolDefinition.builder()
                .name(NAME)
                .description("Fetch a web page by URL and return its readable text content.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "url", Map.of("type", "string",
                                        "description", "The http/https URL to fetch")),
                        "required", new String[]{"url"}))
                .riskLevel(ToolRiskLevel.MEDIUM)
                .build();
    }

    @Override
    public ToolExecutionResult execute(String toolCallId, JsonNode arguments) {
        String url = arguments.path("url").asText("");
        if (url.isBlank()) {
            return ToolExecutionResult.error(toolCallId, NAME, "Missing required field: url");
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolExecutionResult.error(toolCallId, NAME, "Only http/https URLs are allowed");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "MiraAgent/0.0.1")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return ToolExecutionResult.error(toolCallId, NAME,
                        "HTTP " + resp.statusCode() + " from " + url);
            }
            String text = htmlToText(resp.body());
            if (text.length() > MAX_CHARS) {
                text = text.substring(0, MAX_CHARS) + "\n...[truncated]";
            }
            return ToolExecutionResult.success(toolCallId, NAME, text);
        } catch (Exception e) {
            return ToolExecutionResult.error(toolCallId, NAME, "Fetch failed: " + e.getMessage());
        }
    }

    /** 朴素 HTML→文本：剥离 script/style 与标签，折叠空白。 */
    static String htmlToText(String html) {
        if (html == null) {
            return "";
        }
        String noScript = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        String noTags = noScript.replaceAll("(?s)<[^>]+>", " ");
        String unescaped = noTags
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return unescaped.replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll("(?m)^\\s+", "")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
