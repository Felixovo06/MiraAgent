package com.felix.miraagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.felix.miraagent.eval.model.EvalCase;
import com.felix.miraagent.eval.model.JudgeScores;
import com.felix.miraagent.eval.model.RunOutcome;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 评测主入口：读 golden 用例集 → 黑盒跑真实 Agent → 算 Layer1/Layer2 指标 → 出报告。
 *
 * <pre>
 * 运行（需先启动后端 :8080）：
 *   JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./mvnw -q -pl eval -am compile \
 *     exec:java -Dexec.args="--base-url=http://localhost:8080"
 * 可选：-Deval.out=报告路径  -Deval.cases=自定义用例资源名
 * </pre>
 */
public class EvalRunner {

    private final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String baseUrl = arg(args, "--base-url=", "http://localhost:8080");
        String casesRes = System.getProperty("eval.cases", "eval/cases.json");
        String out = System.getProperty("eval.out", "eval-report.json");
        new EvalRunner().run(baseUrl, casesRes, out);
    }

    /** CLI 路径:跑评测、写报告文件、打印 summary。 */
    public void run(String baseUrl, String casesRes, String out) throws Exception {
        ObjectNode report = buildReport(baseUrl, casesRes, System.getProperty("eval.baseline"));
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        Files.writeString(Path.of(out), json);
        System.out.println("\n===== 评测报告 summary =====");
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report.get("summary")));
        if (report.has("diff")) {
            System.out.println("\n----- 与 baseline 对比 -----");
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report.get("diff")));
        }
        System.out.println("报告已写入: " + Path.of(out).toAbsolutePath());
    }

    /** 核心:跑评测并返回完整报告 ObjectNode(供 CLI 与 REST 复用)。baselinePath 可为 null。 */
    public ObjectNode buildReport(String baseUrl, String casesRes, String baselinePath) throws Exception {
        List<EvalCase> cases = loadCases(casesRes);
        AgentEvalClient client = new AgentEvalClient(baseUrl);
        LlmJudge judge = LlmJudge.fromConfig();
        System.out.printf("▶ 评测 %d 条用例 → %s (Judge L3: %s)%n",
                cases.size(), baseUrl, judge.enabled() ? "on" : "off");

        ArrayNode caseReports = mapper.createArrayNode();
        List<RunOutcome> outcomes = new ArrayList<>();
        // Layer3 各维度分数累加
        List<Integer> jRelevance = new ArrayList<>();
        List<Integer> jPersona = new ArrayList<>();
        List<Integer> jToolUse = new ArrayList<>();
        List<Integer> jOverall = new ArrayList<>();
        // 计数器
        int toolSelTotal = 0, toolSelHit = 0;
        int paramTotal = 0, paramHit = 0;
        int noToolTotal = 0, noToolHit = 0;
        int e2eOk = 0;
        int toolLoopTotal = 0, toolLoopOk = 0;
        int assertTotal = 0, assertHit = 0;
        int toolExecTotal = 0, toolExecOk = 0;   // 所有工具执行
        int mcpExecTotal = 0, mcpExecOk = 0;      // MCP 工具执行(名为 mcp__*)
        int reviewTotal = 0, reviewHit = 0;       // 自我改善:后台复盘触发判断是否符合预期
        List<Long> ttfts = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        List<Integer> tokensPerTurn = new ArrayList<>();

        for (EvalCase c : cases) {
            RunOutcome o = client.run(c);
            outcomes.add(o);
            ObjectNode cr = mapper.createObjectNode();
            cr.put("id", c.id());
            cr.put("category", c.category());
            cr.put("ok", o.ok());
            if (o.error() != null) cr.put("error", o.error());
            cr.put("calledTools", String.join(",", o.toolNames()));

            if (o.ok()) {
                e2eOk++;
                ttfts.add(o.ttftMs());
                latencies.add(o.totalMs());
                tokensPerTurn.add(o.inputTokens() + o.outputTokens());
            }
            // Layer1: 工具选择
            if (c.expectedTool() != null) {
                toolSelTotal++;
                boolean hit = o.calledTool(c.expectedTool());
                if (hit) toolSelHit++;
                cr.put("expectedTool", c.expectedTool());
                cr.put("toolSelectionPass", hit);
                // Layer1: 参数准确率（仅在选对工具时评）
                if (hit && c.expectedParams() != null && !c.expectedParams().isEmpty()) {
                    paramTotal++;
                    boolean pp = paramsMatch(o, c.expectedTool(), c.expectedParams());
                    if (pp) paramHit++;
                    cr.put("paramPass", pp);
                }
            }
            // Layer1: no-tool
            if (c.expectsNoTool()) {
                noToolTotal++;
                boolean ok = !o.calledAnyTool();
                if (ok) noToolHit++;
                cr.put("noToolPass", ok);
            }
            // Layer2: 含工具的对话是否完整完成
            if (o.calledAnyTool()) {
                toolLoopTotal++;
                if (o.ok()) toolLoopOk++;
            }
            // Layer1: 工具执行成功率(整体 + MCP),不抛异常且 status=SUCCESS
            for (var te : o.toolStatuses().entrySet()) {
                boolean okStatus = "SUCCESS".equals(te.getValue());
                toolExecTotal++;
                if (okStatus) toolExecOk++;
                if (te.getKey().startsWith("mcp__")) {
                    mcpExecTotal++;
                    if (okStatus) mcpExecOk++;
                }
            }
            // 轻量事实断言
            if (c.assertContains() != null && !c.assertContains().isEmpty()) {
                assertTotal++;
                boolean ap = c.assertContains().stream().allMatch(s -> o.finalContent().contains(s));
                if (ap) assertHit++;
                cr.put("assertPass", ap);
            }
            cr.put("ttftMs", o.ttftMs());
            cr.put("totalMs", o.totalMs());
            cr.put("tokens", o.inputTokens() + o.outputTokens());
            // Layer3: LLM-as-Judge（启用且本轮成功时）
            JudgeScores js = judge.score(c, o);
            if (js != null) {
                ObjectNode jn = cr.putObject("judge");
                putIf(jn, "relevance", js.relevance(), jRelevance);
                putIf(jn, "persona_consistency", js.personaConsistency(), jPersona);
                putIf(jn, "tool_usage", js.toolUsage(), jToolUse);
                putIf(jn, "overall", js.overall(), jOverall);
            }
            // 自我改善(后台复盘是异步的,经公开 trace API 黑盒观测):触发判断是否符合预期
            if (c.expectReview() != null && o.ok()) {
                boolean expect = c.expectReview();
                var review = client.pollReview(o.sessionId(), expect ? 30_000 : 8_000);
                boolean correct = review.triggered() == expect;
                reviewTotal++;
                if (correct) reviewHit++;
                ObjectNode rn = cr.putObject("self_improvement");
                rn.put("expectReview", expect);
                rn.put("triggered", review.triggered());
                rn.put("triggeredBy", review.triggeredBy());
                rn.put("memoryWrites", review.memoryWrites());
                rn.put("skillWrites", review.skillWrites());
            }
            caseReports.add(cr);
            System.out.printf("  %-16s %-12s %s%s%n", c.id(), c.category(),
                    o.ok() ? "OK" : "FAIL", o.error() != null ? " (" + o.error() + ")" : "");
        }

        ObjectNode summary = mapper.createObjectNode();
        // Layer 1
        ObjectNode l1 = summary.putObject("layer1_unit");
        l1.put("tool_selection_accuracy", ratio(toolSelHit, toolSelTotal));
        l1.put("parameter_accuracy", ratio(paramHit, paramTotal));
        l1.put("no_tool_rate", ratio(noToolHit, noToolTotal));
        l1.put("tool_execution_success_rate", ratio(toolExecOk, toolExecTotal));
        l1.put("mcp_execution_success_rate", ratio(mcpExecOk, mcpExecTotal));
        // Layer 2
        ObjectNode l2 = summary.putObject("layer2_chain");
        l2.put("e2e_success_rate", ratio(e2eOk, cases.size()));
        l2.put("tool_loop_success_rate", ratio(toolLoopOk, toolLoopTotal));
        l2.put("ttft_ms_avg", avg(ttfts));
        l2.put("ttft_ms_p95", p95(ttfts));
        l2.put("latency_ms_avg", avg(latencies));
        l2.put("latency_ms_p95", p95(latencies));
        l2.put("avg_tokens_per_turn", avg(tokensPerTurn.stream().map(Long::valueOf).toList()));
        // Layer 3（仅在 Judge 启用且有评分时输出）
        if (judge.enabled() && !jOverall.isEmpty()) {
            ObjectNode l3 = summary.putObject("layer3_quality");
            l3.put("relevance_avg", avgInt(jRelevance));
            l3.put("persona_consistency_avg", avgInt(jPersona));
            l3.put("tool_usage_avg", avgInt(jToolUse));
            l3.put("overall_avg", avgInt(jOverall));
            l3.put("judged_cases", jOverall.size());
        }
        // 自我改善机制(后台复盘触发准确率)
        if (reviewTotal > 0) {
            summary.putObject("self_improvement").put("review_trigger_accuracy", ratio(reviewHit, reviewTotal));
        }
        summary.put("fact_assertion_pass_rate", ratio(assertHit, assertTotal));
        summary.put("total_cases", cases.size());

        ObjectNode report = mapper.createObjectNode();
        report.put("baseUrl", baseUrl);
        report.set("summary", summary);

        // Layer 4：与 baseline 对比（容差 -Deval.tolerance，默认 0.05）
        if (baselinePath != null && Files.exists(Path.of(baselinePath))) {
            double tol = Double.parseDouble(System.getProperty("eval.tolerance", "0.05"));
            JsonNode baseSummary = mapper.readTree(Files.readString(Path.of(baselinePath))).path("summary");
            ObjectNode diff = ReportDiff.diff(mapper, baseSummary, summary, tol);
            report.set("diff", diff);
        }
        report.set("cases", caseReports);
        return report;
    }

    private void putIf(ObjectNode node, String field, Integer v, List<Integer> acc) {
        if (v != null) {
            node.put(field, v);
            acc.add(v);
        }
    }

    private Double avgInt(List<Integer> xs) {
        if (xs.isEmpty()) return null;
        return Math.round(xs.stream().mapToInt(Integer::intValue).average().orElse(0) * 100) / 100.0;
    }

    private boolean paramsMatch(RunOutcome o, String tool, java.util.Map<String, String> expected) {
        String args = o.toolCalls().stream()
                .filter(tc -> tool.equals(tc.get("name")))
                .map(tc -> tc.getOrDefault("arguments", ""))
                .findFirst().orElse("");
        return expected.values().stream().allMatch(args::contains);
    }

    private List<EvalCase> loadCases(String resource) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到用例资源: " + resource);
            }
            return Arrays.asList(mapper.readValue(in, EvalCase[].class));
        }
    }

    private static String arg(String[] args, String prefix, String def) {
        return Arrays.stream(args).filter(a -> a.startsWith(prefix))
                .map(a -> a.substring(prefix.length())).findFirst().orElse(def);
    }

    /** 命中率，保留 3 位；分母为 0 返回 null（不适用）。 */
    private Double ratio(int hit, int total) {
        if (total == 0) return null;
        return Math.round(hit * 1000.0 / total) / 1000.0;
    }

    private Long avg(List<Long> xs) {
        if (xs.isEmpty()) return null;
        return Math.round(xs.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    private Long p95(List<Long> xs) {
        if (xs.isEmpty()) return null;
        List<Long> sorted = new ArrayList<>(xs);
        sorted.sort(Long::compareTo);
        int idx = (int) Math.ceil(0.95 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
