package com.felix.miraagent.tools.impl;

import com.felix.miraagent.tools.ToolDefinition;
import com.felix.miraagent.tools.ToolDispatchContext;
import com.felix.miraagent.tools.ToolRiskLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RiskThresholdToolPermissionPolicyTest {

    private ToolDefinition tool(ToolRiskLevel risk) {
        return ToolDefinition.builder().name("t").description("d")
                .inputSchema(java.util.Map.of()).riskLevel(risk).build();
    }

    private final ToolDispatchContext ctx = ToolDispatchContext.builder().runId("r").build();

    @Test
    void mediumThresholdAllowsLowAndMediumDeniesHigh() {
        var policy = new RiskThresholdToolPermissionPolicy(ToolRiskLevel.MEDIUM);
        assertTrue(policy.isAllowed(tool(ToolRiskLevel.LOW), ctx));
        assertTrue(policy.isAllowed(tool(ToolRiskLevel.MEDIUM), ctx));
        assertFalse(policy.isAllowed(tool(ToolRiskLevel.HIGH), ctx));
        assertFalse(policy.isAllowed(tool(ToolRiskLevel.CRITICAL), ctx));
    }

    @Test
    void highThresholdElevatesToAllowHigh() {
        var policy = new RiskThresholdToolPermissionPolicy(ToolRiskLevel.HIGH);
        assertTrue(policy.isAllowed(tool(ToolRiskLevel.HIGH), ctx));
        assertFalse(policy.isAllowed(tool(ToolRiskLevel.CRITICAL), ctx));
    }

    @Test
    void nullThresholdDefaultsToMedium() {
        var policy = new RiskThresholdToolPermissionPolicy(null);
        assertEquals(ToolRiskLevel.MEDIUM, policy.getMaxAllowed());
        assertFalse(policy.isAllowed(tool(ToolRiskLevel.HIGH), ctx));
    }
}
