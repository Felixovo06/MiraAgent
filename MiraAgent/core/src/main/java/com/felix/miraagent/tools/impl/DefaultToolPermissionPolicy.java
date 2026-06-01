package com.felix.miraagent.tools.impl;

import com.felix.miraagent.tools.ToolDefinition;
import com.felix.miraagent.tools.ToolDispatchContext;
import com.felix.miraagent.tools.ToolPermissionPolicy;
import com.felix.miraagent.tools.ToolRiskLevel;

public class DefaultToolPermissionPolicy implements ToolPermissionPolicy {

    @Override
    public boolean isAllowed(ToolDefinition tool, ToolDispatchContext context) {
        return tool.getRiskLevel() == ToolRiskLevel.LOW
                || tool.getRiskLevel() == ToolRiskLevel.MEDIUM;
    }
}
