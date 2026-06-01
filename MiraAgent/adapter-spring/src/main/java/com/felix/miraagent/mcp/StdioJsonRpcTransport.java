package com.felix.miraagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * stdio 传输：启动子进程，按行分隔的 JSON-RPC 经 stdin/stdout 通信（MCP 官方主流方式）。
 * <p>请求串行化（synchronized，同一时刻仅一个 in-flight），按 id 配对响应，
 * 跳过服务端穿插的通知；stderr 后台 drain 进日志，避免缓冲区阻塞。
 * <p>长期运行健壮性：每次读响应有<b>超时</b>（防 server 卡死导致线程永久阻塞），
 * 子进程死亡/卡死后可 {@link #reconnect()} 重启（配合 DefaultMcpClient 重握手自愈）。
 */
public class StdioJsonRpcTransport implements JsonRpcTransport {

    private static final Logger log = LoggerFactory.getLogger(StdioJsonRpcTransport.class);
    private static final long DEFAULT_READ_TIMEOUT_MS = 30_000;

    private final String serverId;
    private final String command;
    private final List<String> args;
    private final Map<String, String> env;
    private final ObjectMapper mapper;
    private final long readTimeoutMillis;
    private final AtomicLong idSeq = new AtomicLong(0);
    private final ExecutorService readExecutor;

    private volatile Process process;
    private volatile BufferedWriter stdin;
    private volatile BufferedReader stdout;
    private volatile boolean closed = false;

    public StdioJsonRpcTransport(String serverId, String command, List<String> args,
                                 Map<String, String> env, ObjectMapper mapper) {
        this(serverId, command, args, env, mapper, DEFAULT_READ_TIMEOUT_MS);
    }

    public StdioJsonRpcTransport(String serverId, String command, List<String> args,
                                 Map<String, String> env, ObjectMapper mapper, long readTimeoutMillis) {
        this.serverId = serverId;
        this.command = command;
        this.args = args;
        this.env = env;
        this.mapper = mapper;
        this.readTimeoutMillis = readTimeoutMillis > 0 ? readTimeoutMillis : DEFAULT_READ_TIMEOUT_MS;
        this.readExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mcp-read-" + serverId);
            t.setDaemon(true);
            return t;
        });
        launch();
    }

    private synchronized void launch() {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(command);
            if (args != null) {
                cmd.addAll(args);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (env != null && !env.isEmpty()) {
                pb.environment().putAll(env);
            }
            this.process = pb.start();
            this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            drainStderr(process);
        } catch (IOException e) {
            throw new McpException("Failed to start MCP stdio server '" + serverId + "': " + e.getMessage(), e);
        }
    }

    private void drainStderr(Process p) {
        Thread t = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    log.debug("[mcp:{} stderr] {}", serverId, line);
                }
            } catch (IOException ignored) {
                // process ended
            }
        }, "mcp-stderr-" + serverId);
        t.setDaemon(true);
        t.start();
    }

    @Override
    public synchronized JsonNode request(String method, JsonNode params) throws McpException {
        long id = idSeq.incrementAndGet();
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        writeLine(req);

        // 读取直到拿到匹配 id 的响应（跳过通知 / 其它 id），每次读有超时
        while (true) {
            String line = readLineWithTimeout();
            JsonNode node;
            try {
                node = mapper.readTree(line);
            } catch (IOException e) {
                throw new McpException("Invalid JSON from MCP server '" + serverId + "': " + line, e);
            }
            if (!node.has("id") || node.get("id").asLong() != id) {
                continue;
            }
            if (node.has("error") && !node.get("error").isNull()) {
                JsonNode err = node.get("error");
                throw new McpException("MCP server '" + serverId + "' error: "
                        + err.path("message").asText(err.toString()));
            }
            return node.path("result");
        }
    }

    @Override
    public synchronized void notify(String method, JsonNode params) throws McpException {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        writeLine(req);
    }

    private void writeLine(JsonNode node) {
        try {
            stdin.write(mapper.writeValueAsString(node));
            stdin.write("\n");
            stdin.flush();
        } catch (IOException e) {
            throw new McpException("Failed to write to MCP server '" + serverId + "'", e);
        }
    }

    /** 在独立线程上读取一行并施加超时；超时则销毁子进程并抛异常（避免线程永久阻塞）。 */
    private String readLineWithTimeout() {
        final BufferedReader reader = this.stdout;
        Future<String> future = readExecutor.submit(reader::readLine);
        try {
            String line = future.get(readTimeoutMillis, TimeUnit.MILLISECONDS);
            if (line == null) {
                throw new McpException("MCP server '" + serverId + "' closed the connection");
            }
            return line;
        } catch (TimeoutException e) {
            future.cancel(true);
            destroyProcess();   // 关闭流以解除阻塞的 readLine
            throw new McpException("MCP server '" + serverId + "' read timed out after "
                    + readTimeoutMillis + "ms");
        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("Failed to read from MCP server '" + serverId + "': " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isHealthy() {
        return !closed && process != null && process.isAlive();
    }

    @Override
    public synchronized void reconnect() throws McpException {
        if (closed) {
            throw new McpException("MCP transport '" + serverId + "' is closed");
        }
        log.info("Reconnecting MCP stdio server '{}'", serverId);
        destroyProcess();
        launch();
    }

    private void destroyProcess() {
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (IOException ignored) {
        }
        Process p = this.process;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        destroyProcess();
        readExecutor.shutdownNow();
    }
}
