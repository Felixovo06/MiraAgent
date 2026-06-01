package com.felix.miraagent.tools.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.felix.miraagent.model.ToolCall;
import com.felix.miraagent.tools.ToolDispatchContext;
import com.felix.miraagent.tools.ToolExecutionResult;
import com.felix.miraagent.tools.ToolRiskLevel;
import com.felix.miraagent.tools.ToolStatus;
import com.felix.miraagent.tools.impl.DefaultToolDispatcher;
import com.felix.miraagent.tools.impl.InMemoryToolRegistry;
import com.felix.miraagent.tools.impl.RiskThresholdToolPermissionPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BuiltinIoToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ---------- web_fetch ----------

    @Test
    void htmlToTextStripsTagsScriptsAndEntities() {
        String html = "<html><head><style>x{}</style><script>var a=1;</script></head>"
                + "<body><h1>Hi&amp;Bye</h1><p>line</p></body></html>";
        String text = WebFetchToolHandler.htmlToText(html);
        assertFalse(text.contains("var a"));
        assertFalse(text.contains("<"));
        assertTrue(text.contains("Hi&Bye"));
        assertTrue(text.contains("line"));
    }

    @Test
    void webFetchReadsRealHttpServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "<html><body><p>hello mira</p></body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ObjectNode args = mapper.createObjectNode();
            args.put("url", "http://127.0.0.1:" + port + "/");
            ToolExecutionResult r = new WebFetchToolHandler().execute("c1", args);
            assertEquals(ToolStatus.SUCCESS, r.getStatus());
            assertTrue(r.getModelVisibleContent().contains("hello mira"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void webFetchRejectsNonHttpScheme() {
        ObjectNode args = mapper.createObjectNode();
        args.put("url", "file:///etc/passwd");
        assertEquals(ToolStatus.ERROR, new WebFetchToolHandler().execute("c1", args).getStatus());
    }

    // ---------- file_read / file_write ----------

    @Test
    void fileWriteThenReadRoundTrip(@TempDir Path tmp) {
        var write = new FileWriteToolHandler(tmp.toString());
        var read = new FileReadToolHandler(tmp.toString());

        ObjectNode wArgs = mapper.createObjectNode();
        wArgs.put("path", "notes/a.txt");
        wArgs.put("content", "记得复习");
        assertEquals(ToolStatus.SUCCESS, write.execute("w", wArgs).getStatus());

        ObjectNode rArgs = mapper.createObjectNode();
        rArgs.put("path", "notes/a.txt");
        ToolExecutionResult r = read.execute("r", rArgs);
        assertEquals(ToolStatus.SUCCESS, r.getStatus());
        assertEquals("记得复习", r.getModelVisibleContent());
    }

    @Test
    void pathTraversalIsRejected(@TempDir Path tmp) {
        var read = new FileReadToolHandler(tmp.toString());
        ObjectNode args = mapper.createObjectNode();
        args.put("path", "../../etc/passwd");
        assertEquals(ToolStatus.ERROR, read.execute("r", args).getStatus());
    }

    @Test
    void readMissingFileIsError(@TempDir Path tmp) {
        var read = new FileReadToolHandler(tmp.toString());
        ObjectNode args = mapper.createObjectNode();
        args.put("path", "nope.txt");
        assertEquals(ToolStatus.ERROR, read.execute("r", args).getStatus());
    }

    // ---------- 危险工具权限门控 ----------

    @Test
    void fileWriteGatedByPermissionThreshold(@TempDir Path tmp) throws Exception {
        var registry = new InMemoryToolRegistry();
        registry.register(FileWriteToolHandler.definition(), new FileWriteToolHandler(tmp.toString()));
        var dispatcher = new DefaultToolDispatcher(registry);

        ToolCall call = ToolCall.builder().id("c").name("file_write")
                .arguments("{\"path\":\"x.txt\",\"content\":\"hi\"}").build();

        // 默认阈值 MEDIUM：HIGH 工具被拒
        ToolDispatchContext mediumCtx = ToolDispatchContext.builder().runId("r")
                .permissionPolicy(new RiskThresholdToolPermissionPolicy(ToolRiskLevel.MEDIUM)).build();
        assertEquals(ToolStatus.DENIED, dispatcher.dispatchOne(call, mediumCtx).getStatus());
        assertFalse(Files.exists(tmp.resolve("x.txt")));

        // 抬高到 HIGH：放行并真正写入
        ToolDispatchContext highCtx = ToolDispatchContext.builder().runId("r")
                .permissionPolicy(new RiskThresholdToolPermissionPolicy(ToolRiskLevel.HIGH)).build();
        assertEquals(ToolStatus.SUCCESS, dispatcher.dispatchOne(call, highCtx).getStatus());
        assertTrue(Files.exists(tmp.resolve("x.txt")));
    }

    @Test
    void riskLevelsDeclaredCorrectly() {
        assertEquals(ToolRiskLevel.MEDIUM, WebFetchToolHandler.definition().getRiskLevel());
        assertEquals(ToolRiskLevel.MEDIUM, FileReadToolHandler.definition().getRiskLevel());
        assertEquals(ToolRiskLevel.HIGH, FileWriteToolHandler.definition().getRiskLevel());
    }
}
