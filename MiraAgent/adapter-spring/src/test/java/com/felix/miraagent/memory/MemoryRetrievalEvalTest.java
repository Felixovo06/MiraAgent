package com.felix.miraagent.memory;

import com.felix.miraagent.memory.jdbc.JdbcHybridMemoryRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer1 记忆检索质量评测：对一个带标注的记忆池跑混合检索（向量 RRF + 词法），
 * 计算 Precision / Recall / Top-1。属"组件级评测"，落在记忆检索器所在模块，
 * 不污染黑盒 eval 模块；依赖真实 embedding(DashScope) + 云 PG，故按需运行：
 *
 * <pre>
 * JAVA_HOME=... ./mvnw -pl adapter-spring test -Dmira.it.postgres=true \
 *   -Dtest=MemoryRetrievalEvalTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@EnabledIfSystemProperty(named = "mira.it.postgres", matches = "true")
@SpringBootTest(properties = {
        "memory.base-dir=target/test-memory-retrieval-eval",
        "mira.weixin.enabled=false",
        "spring.sql.init.mode=never"
})
@ActiveProfiles("local")
class MemoryRetrievalEvalTest {

    private static final String USER = "mem-eval-user";

    @Autowired MemoryRetriever retriever;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemoryIndexRepository indexRepository;
    @Autowired(required = false) EmbeddingClient embeddingClient;

    /** 带标注的记忆池：id -> 文本。 */
    private static final Map<String, String> POOL = Map.of(
            "m_jazz", "用户说自己很喜欢听爵士乐，尤其是钢琴曲",
            "m_guitar", "用户最近在学弹吉他",
            "m_cat", "用户养了一只橘猫，名字叫橘子",
            "m_hike", "用户上周末去爬了香山",
            "m_job", "用户是一名后端软件工程师",
            "m_coffee", "用户每天早上都要喝一杯手冲咖啡");

    /** 查询 -> 期望相关记忆 id 集合（人工标注）。 */
    private static final List<Query> QUERIES = List.of(
            new Query("用户喜欢什么音乐", Set.of("m_jazz", "m_guitar")),
            new Query("用户养了什么宠物", Set.of("m_cat")),
            new Query("用户的职业是什么", Set.of("m_job")));

    record Query(String text, Set<String> relevant) {}

    @AfterEach
    void cleanup() {
        try {
            indexRepository.deleteAll(USER);
        } catch (Exception ignored) {
        }
    }

    @Test
    void retrievalPrecisionRecallTop1() {
        org.junit.jupiter.api.Assumptions.assumeTrue(embeddingClient != null,
                "需要配置 embedding(mira.embedding.*) 才能评向量检索");

        // 1) 播种带标注的记忆池 + 真实 embedding
        Instant now = Instant.now();
        indexRepository.deleteAll(USER);
        POOL.forEach((id, text) -> {
            indexRepository.save(MemoryIndex.builder()
                    .id(id).userId(USER)
                    .scope(MemoryScope.GLOBAL)
                    .category(MemoryCategory.PROFILE)
                    .contentPreview(text)
                    .sourceUri("memory/eval/" + id)
                    .retrievalTerms(List.of())
                    .createdAt(now).updatedAt(now)
                    .build());
            String vec = "[" + embeddingClient.embed(text).stream()
                    .map(Object::toString).collect(Collectors.joining(",")) + "]";
            jdbc.update("UPDATE memory_index SET embedding = ?::vector WHERE id = ?", vec, id);
        });

        // 诊断:确认 embedding 列已落库(否则向量检索退化为纯词法)
        Integer embedded = jdbc.queryForObject(
                "SELECT count(*) FROM memory_index WHERE user_id = ? AND embedding IS NOT NULL",
                Integer.class, USER);
        System.out.println("embedding 已写入行数: " + embedded + " / " + POOL.size());

        // 2) 逐查询算 P / R / Top-1
        int k = 3;
        double sumP = 0, sumR = 0;
        int top1Hits = 0;
        System.out.println("\n===== 记忆检索 P/R 评测 (top-" + k + ") =====");
        for (Query q : QUERIES) {
            MemoryRetrieveResult res = retriever.retrieve(MemoryRetrieveRequest.builder()
                    .userId(USER).query(q.text()).limit(k).build());
            List<String> got = res.getHits().stream().map(MemoryIndex::getId).toList();
            long relevantRetrieved = got.stream().filter(q.relevant()::contains).count();
            double precision = got.isEmpty() ? 0 : (double) relevantRetrieved / got.size();
            double recall = (double) relevantRetrieved / q.relevant().size();
            boolean top1 = !got.isEmpty() && q.relevant().contains(got.get(0));
            if (top1) top1Hits++;
            sumP += precision;
            sumR += recall;
            System.out.printf("  「%s」-> %s | P=%.2f R=%.2f top1=%s%n",
                    q.text(), got, precision, recall, top1);
        }
        double avgP = sumP / QUERIES.size();
        double avgR = sumR / QUERIES.size();
        double top1Rate = (double) top1Hits / QUERIES.size();
        // Precision 受 top-k 与相关集大小约束:每查询最高 = min(k, |relevant|)/k,
        // 故 P 不宜定绝对高基线,而以"逼近理论上限"为目标;Recall/Top-1 才反映召回质量。
        double idealP = QUERIES.stream()
                .mapToDouble(q -> Math.min(k, q.relevant().size()) / (double) k)
                .average().orElse(0);
        System.out.printf("== 汇总: Precision=%.3f (理论上限 %.3f)  Recall=%.3f  Top-1=%.3f ==%n",
                avgP, idealP, avgR, top1Rate);
        System.out.println("(目标基线: Precision 逼近理论上限  Recall≥0.90  Top-1≥0.90)");

        // 这是"测量型"评测:职责是出 P/R 数字,不以模型/检索质量硬性 gate(故不对阈值断言)。
        // 只校验机制可用:确为混合检索器、有结果返回。质量阈值作为基线供回归跟踪。
        assertNotNull(retriever);
        assertTrue(retriever instanceof JdbcHybridMemoryRetriever, "应为向量+词法混合检索器");
        assertTrue(embedded != null && embedded == POOL.size(), "所有记忆都应成功写入 embedding");
    }
}
