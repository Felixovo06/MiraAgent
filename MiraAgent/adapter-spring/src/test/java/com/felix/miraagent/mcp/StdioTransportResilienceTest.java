package com.felix.miraagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * stdio 传输长期运行健壮性：读超时（防卡死永久阻塞）+ 子进程重连（死亡后自愈）。
 * 环境无 python3 时优雅跳过。
 */
class StdioTransportResilienceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private boolean python3Available() {
        try {
            return new ProcessBuilder("python3", "--version").start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private Path echoScript() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            for (String rel : new String[]{"scripts/mcp/echo_mcp_server.py", "MiraAgent/scripts/mcp/echo_mcp_server.py"}) {
                Path c = dir.resolve(rel);
                if (Files.exists(c)) {
                    return c;
                }
            }
            dir = dir.getParent();
        }
        return null;
    }

    @Test
    void readTimeoutThrowsInsteadOfBlockingForever() {
        assumeTrue(python3Available(), "python3 not available");
        // 读一行后永久 sleep、从不响应的假 server
        var transport = new StdioJsonRpcTransport("hang", "python3",
                List.of("-c", "import sys,time; sys.stdin.readline(); time.sleep(30)"),
                null, mapper, 800);
        try {
            long start = System.currentTimeMillis();
            McpException ex = assertThrows(McpException.class,
                    () -> transport.request("initialize", mapper.createObjectNode()));
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(ex.getMessage().contains("timed out"), "应为超时异常: " + ex.getMessage());
            assertTrue(elapsed < 5000, "应在超时附近返回，而非永久阻塞，实际 " + elapsed + "ms");
        } finally {
            transport.close();
        }
    }

    @Test
    void reconnectRestartsProcessAndStaysUsable() {
        assumeTrue(python3Available(), "python3 not available");
        Path script = echoScript();
        assumeTrue(script != null, "echo server not found");

        var transport = new StdioJsonRpcTransport("echo", "python3",
                List.of(script.toString()), null, mapper);
        try {
            assertTrue(transport.isHealthy());
            assertTrue(transport.request("tools/list", mapper.createObjectNode()).path("tools").isArray());

            transport.reconnect();   // 重启子进程
            assertTrue(transport.isHealthy(), "重连后应恢复健康");
            // 重启后的新进程仍可正常响应
            assertTrue(transport.request("tools/list", mapper.createObjectNode()).path("tools").isArray());
        } finally {
            transport.close();
        }
        assertFalse(transport.isHealthy(), "close 后应不健康");
    }
}
