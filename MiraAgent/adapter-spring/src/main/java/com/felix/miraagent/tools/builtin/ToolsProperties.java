package com.felix.miraagent.tools.builtin;

import com.felix.miraagent.tools.ToolRiskLevel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具权限阈值。max-risk-level 以下（含）的工具放行，以上被门控。
 * 默认 MEDIUM——HIGH/CRITICAL 危险工具（如 file_write）默认被拦，需显式抬高。
 */
@Data
@ConfigurationProperties(prefix = "mira.tools")
public class ToolsProperties {
    private ToolRiskLevel maxRiskLevel = ToolRiskLevel.MEDIUM;
}
