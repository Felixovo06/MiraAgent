package com.felix.miraagent.tools.impl;

import com.felix.miraagent.tools.ToolDefinition;
import com.felix.miraagent.tools.ToolDispatchContext;
import com.felix.miraagent.tools.ToolPermissionPolicy;
import com.felix.miraagent.tools.ToolRiskLevel;

/**
 * 按风险阈值放行的权限策略：风险等级 ≤ maxAllowed 才允许。
 * <p>默认阈值 MEDIUM——HIGH/CRITICAL（写入、删除、外部发送等危险工具）默认被门控，
 * 返回 DENIED 并落 PERMISSION_DENIED trace；需运营/用户显式把阈值抬到 HIGH 才放行，
 * 即"危险工具的明确确认流程"。
 */
public class RiskThresholdToolPermissionPolicy implements ToolPermissionPolicy {

    private final ToolRiskLevel maxAllowed;

    public RiskThresholdToolPermissionPolicy(ToolRiskLevel maxAllowed) {
        this.maxAllowed = maxAllowed == null ? ToolRiskLevel.MEDIUM : maxAllowed;
    }

    @Override
    public boolean isAllowed(ToolDefinition tool, ToolDispatchContext context) {
        return tool.getRiskLevel().ordinal() <= maxAllowed.ordinal();
    }

    public ToolRiskLevel getMaxAllowed() {
        return maxAllowed;
    }
}
