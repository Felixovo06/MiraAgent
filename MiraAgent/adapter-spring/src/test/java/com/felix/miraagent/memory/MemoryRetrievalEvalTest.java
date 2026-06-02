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

    /**
     * 长期记忆抗噪评测：把若干"10 天前"的目标事实，埋进大量"较新"的噪声记忆里，
     * 验证老记忆仍能被自然语言查询召回——这才真正覆盖"跨度长 + 中间噪声多"的长期记忆，
     * 而非黑盒评测里"同会话上下文"那种假记忆。噪声含 hard negative（与查询共享话题但非答案）。
     */
    @Test
    void oldMemorySurvivesAmongRecentNoise() {
        org.junit.jupiter.api.Assumptions.assumeTrue(embeddingClient != null,
                "需要配置 embedding(mira.embedding.*) 才能评向量检索");
        indexRepository.deleteAll(USER);

        Instant tenDaysAgo = Instant.now().minusSeconds(10L * 24 * 3600);
        Instant recent = Instant.now();

        // 目标:10 天前写入的稳定事实,覆盖多类型话题(普适性),各配一个自然语言查询
        List<Target> targets = List.of(
                new Target("t_allergy", "用户对花生过敏，吃任何东西都要避开花生", "我对什么食物过敏来着"),
                new Target("t_pet", "用户养了一只布偶猫，名字叫汤圆", "我家那只猫叫什么名字"),
                new Target("t_job", "用户是一名后端工程师，目前在杭州工作", "我是做什么工作的"),
                new Target("t_hobby", "用户每个周末都喜欢去爬山", "我周末一般喜欢做什么"),
                new Target("t_music", "用户特别喜欢听爵士钢琴曲", "我平时爱听哪种音乐"),
                new Target("t_anniversary", "用户和女朋友的纪念日是 5 月 20 日", "我和女朋友的纪念日是哪天"));

        // 噪声:大量较新的无关记忆,含 hard negative(与某查询共享词/话题但不是答案),专压区分度
        List<String> noise = List.of(
                "用户今天觉得天气有点闷热不太舒服",
                "用户最近在研究各种手冲咖啡的豆子",
                "用户帮邻居照看过一条金毛犬",            // 近 pet:是狗不是猫
                "用户最近工作上的压力比较大",            // 近 job
                "用户说自己其实不太能吃辣",              // 近 allergy:口味不是过敏
                "用户上个月去三亚旅游晒黑了",
                "用户在考虑要不要换一份新工作",          // 近 job
                "用户周末有时会在家打游戏",              // 近 hobby
                "用户提到楼下新开了一家奶茶店",
                "用户最近在追一部悬疑电视剧",
                "用户说他表弟也养了猫",                  // 近 pet:别人的猫
                "用户喜欢在通勤路上听播客",              // 近 music:播客不是爵士
                "用户买了一双新的跑鞋",
                "用户的同事下周要请婚假",                // 近 anniversary:别人的
                "用户最近在背英语单词准备考试",
                "用户家里的绿萝养得不错",
                "用户说自己对花粉有点敏感",              // 近 allergy:花粉不是花生
                "用户中午经常点轻食沙拉",
                "用户在学着自己做木工",
                "用户喜欢收集各地的冰箱贴",
                "用户最近迷上了拼乐高",
                "用户偶尔会去健身房撸铁",                // 近 hobby
                "用户说北京的冬天太干燥",
                "用户的手机最近老是卡顿",
                "用户在看一本关于历史的书",
                "用户喜欢用机械键盘打字",
                "用户养过一只乌龟但是后来送人了",        // 近 pet:乌龟且已送走
                "用户最近睡眠质量不太好",
                "用户说想学一门乐器比如吉他",            // 近 music:想学吉他不是听爵士
                "用户周末去看了场电影",                  // 近 hobby
                "用户最近在减糖控制饮食",
                "用户的车该做保养了",
                "用户喜欢逛二手书店",
                "用户说公司食堂的菜越来越难吃",
                "用户在攒钱想买个相机",
                "用户最近换了个发型",
                "用户说他爸妈下个月来看他",
                "用户喜欢雨天待在家里",
                "用户在用一个新的记账 App",
                "用户最近报了个线上课程");

        // 播种:目标回填到 10 天前;噪声为"现在"写入
        for (Target t : targets) {
            seedMemory(t.id(), t.content(), tenDaysAgo);
        }
        int idx = 0;
        for (String n : noise) {
            seedMemory("noise_" + (idx++), n, recent);
        }
        System.out.printf("%n===== 长期记忆抗噪评测: %d 条「10 天前」目标 埋入 %d 条较新噪声 =====%n",
                targets.size(), noise.size());

        int k = 5;
        int recallHits = 0, top1Hits = 0;
        for (Target t : targets) {
            List<String> got = retriever.retrieve(MemoryRetrieveRequest.builder()
                            .userId(USER).query(t.query()).limit(k).build())
                    .getHits().stream().map(MemoryIndex::getId).toList();
            boolean inTopK = got.contains(t.id());
            boolean top1 = !got.isEmpty() && got.get(0).equals(t.id());
            if (inTopK) recallHits++;
            if (top1) top1Hits++;
            System.out.printf("  「%s」-> %s | 命中=%s top1=%s%n", t.query(), got, inTopK, top1);
        }
        double recallAtK = (double) recallHits / targets.size();
        double top1Rate = (double) top1Hits / targets.size();
        System.out.printf("== 汇总: Recall@%d=%.3f  Top-1=%.3f (目标 %d / 噪声 %d) ==%n",
                k, recallAtK, top1Rate, targets.size(), noise.size());

        // 普适性 gate:10 天前的目标记忆,埋在大量较新噪声里,仍应几乎全部能在 top-k 召回。
        assertTrue(recallAtK >= 0.8,
                "长期记忆抗噪不达标: Recall@" + k + "=" + recallAtK + "(老目标被较新噪声淹没)");
    }

    private record Target(String id, String content, String query) {}

    /** 播种一条记忆:落库 + 真实 embedding + 回填指定时间戳(save() 强制 now(),故另用 UPDATE 改回)。 */
    private void seedMemory(String id, String content, Instant ts) {
        indexRepository.save(MemoryIndex.builder()
                .id(id).userId(USER)
                .scope(MemoryScope.GLOBAL)
                .category(MemoryCategory.PROFILE)
                .contentPreview(content)
                .sourceUri("memory/eval/" + id)
                .retrievalTerms(List.of())
                .createdAt(ts).updatedAt(ts)
                .build());
        String vec = "[" + embeddingClient.embed(content).stream()
                .map(Object::toString).collect(Collectors.joining(",")) + "]";
        jdbc.update("UPDATE memory_index SET embedding = ?::vector, created_at = ?, updated_at = ? WHERE id = ?",
                vec, java.sql.Timestamp.from(ts), java.sql.Timestamp.from(ts), id);
    }
}
