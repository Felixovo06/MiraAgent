package com.felix.miraagent.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "memory")
public class MemoryProperties {
    private String baseDir = System.getProperty("user.home") + "/.miraagent/memory";

    private Dedup dedup = new Dedup();

    /** 写入前去重阈值。lexical* 为 pg_trgm 字面相似度，semantic* 为向量 cosine，均 0-1。 */
    @Data
    public static class Dedup {
        /** 关闭时所有写入直通，不做去重。 */
        private boolean enabled = true;
        /** [near, exact) 视为近似：更新既有卡片。 */
        private double nearThreshold = 0.6;
        /** >= exact 视为完全重复：仅强化既有、不新增卡片。 */
        private double exactThreshold = 0.82;
        /** 语义去重下水位：向量 cosine >= 此值视为同一事实（catch 同义改写）；需有 EmbeddingClient。 */
        private double semanticNearThreshold = 0.86;
        /** 语义去重上水位：cosine >= 此值按完全重复处理（仅强化、不改措辞）。 */
        private double semanticExactThreshold = 0.92;
    }
}
