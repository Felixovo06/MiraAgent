package com.felix.miraagent.experience;

import com.felix.miraagent.memory.MemoryCategory;
import com.felix.miraagent.memory.MemoryScope;
import com.felix.miraagent.memory.MemoryWriteRequest;
import com.felix.miraagent.memory.SerializedMemoryWriter;
import com.felix.miraagent.skill.SkillCreateCommand;
import com.felix.miraagent.skill.SkillManager;
import com.felix.miraagent.skill.SkillPatch;
import lombok.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 把 ExperienceReviewResult 落地：用户事实 → memory（SerializedMemoryWriter），
 * 流程技能 → skill（SkillManager，create 内部去重）。所有写入带 source_trace_id。
 * 隐私隔离由 schema 保证（skill_writes 无事实字段），此处不需脱敏。
 */
public class ExperienceApplier {

    private static final Logger log = LoggerFactory.getLogger(ExperienceApplier.class);

    private final SerializedMemoryWriter memoryWriter; // nullable
    private final SkillManager skillManager;           // nullable

    public ExperienceApplier(SerializedMemoryWriter memoryWriter, SkillManager skillManager) {
        this.memoryWriter = memoryWriter;
        this.skillManager = skillManager;
    }

    public ApplyResult apply(ExperienceReviewResult result, ExperienceReviewRequest request) {
        if (result == null || !result.isWorthSaving()) {
            return new ApplyResult(0, 0);
        }
        int memories = 0;
        int skills = 0;

        if (memoryWriter != null) {
            for (MemoryWritePlan plan : result.getMemoryWrites()) {
                try {
                    // 异步提交：复盘落库不阻塞后台复盘线程，失败仅记日志、不穿透。
                    memoryWriter.submitAsync(MemoryWriteRequest.builder()
                            .memoryId(UUID.randomUUID().toString())
                            .userId(request.getUserId())
                            .characterId("character".equalsIgnoreCase(plan.getScope()) ? request.getCharacterId() : null)
                            .scope(toScope(plan.getScope()))
                            .category(toCategory(plan.getKind()))
                            .content(plan.getContent())
                            .sourceSessionId(request.getSessionId())
                            .sourceTraceId(plan.getSourceTraceId())
                            .confidence(toConfidencePercent(plan.getConfidence()))
                            .build())
                            .whenComplete((r, ex) -> {
                                if (ex != null) {
                                    log.warn("Failed to apply memory write: {}", ex.getMessage());
                                }
                            });
                    memories++;
                } catch (Exception e) {
                    log.warn("Failed to enqueue memory write: {}", e.getMessage());
                }
            }
        }

        if (skillManager != null) {
            for (SkillWritePlan plan : result.getSkillWrites()) {
                try {
                    if ("patch".equalsIgnoreCase(plan.getOp()) && plan.getTargetSkillId() != null) {
                        skillManager.patch(SkillPatch.builder()
                                .skillId(plan.getTargetSkillId())
                                .newDescription(plan.getDescription())
                                .appendBody(renderBody(plan))
                                .note("background review patch")
                                .sourceTraceId(plan.getSourceTraceId())
                                .sourceSessionId(request.getSessionId())
                                .build());
                    } else {
                        skillManager.create(SkillCreateCommand.builder()
                                .name(plan.getName())
                                .description(plan.getDescription())
                                .body(renderBody(plan))
                                .source("background_review")
                                .sourceTraceId(plan.getSourceTraceId())
                                .sourceSessionId(request.getSessionId())
                                .build());
                    }
                    skills++;
                } catch (Exception e) {
                    log.warn("Failed to apply skill write: {}", e.getMessage());
                }
            }
        }
        return new ApplyResult(memories, skills);
    }

    private MemoryScope toScope(String scope) {
        return "character".equalsIgnoreCase(scope) ? MemoryScope.CHARACTER : MemoryScope.GLOBAL;
    }

    private MemoryCategory toCategory(String kind) {
        if (kind == null) {
            return MemoryCategory.EVENT;
        }
        return switch (kind.toLowerCase()) {
            case "preference" -> MemoryCategory.PREFERENCE;
            case "relationship" -> MemoryCategory.RELATIONSHIP;
            default -> MemoryCategory.EVENT; // fact / tool_experience / 其它
        };
    }

    private int toConfidencePercent(double confidence) {
        if (confidence <= 0) {
            return 80;
        }
        return Math.max(0, Math.min(100, (int) Math.round(confidence * 100)));
    }

    private String renderBody(SkillWritePlan plan) {
        StringBuilder sb = new StringBuilder();
        if (plan.getWhenToUse() != null && !plan.getWhenToUse().isBlank()) {
            sb.append("## 何时使用\n").append(plan.getWhenToUse()).append("\n\n");
        }
        if (!plan.getSteps().isEmpty()) {
            sb.append("## 执行步骤\n");
            int i = 1;
            for (String step : plan.getSteps()) {
                sb.append(i++).append(". ").append(step).append("\n");
            }
            sb.append("\n");
        }
        if (!plan.getToolSuggestions().isEmpty()) {
            sb.append("## 工具建议\n");
            for (String t : plan.getToolSuggestions()) {
                sb.append("- ").append(t).append("\n");
            }
            sb.append("\n");
        }
        if (!plan.getChecklist().isEmpty()) {
            sb.append("## 检查清单\n");
            for (String c : plan.getChecklist()) {
                sb.append("- ").append(c).append("\n");
            }
        }
        return sb.toString().trim();
    }

    @Value
    public static class ApplyResult {
        int memoriesWritten;
        int skillsWritten;
    }
}
