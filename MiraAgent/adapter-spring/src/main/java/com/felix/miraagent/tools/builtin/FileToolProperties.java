package com.felix.miraagent.tools.builtin;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文件读写工具的沙箱根目录。所有路径强制限制在 base-dir 内（防穿越）。
 */
@Data
@ConfigurationProperties(prefix = "mira.tools.file")
public class FileToolProperties {
    private String baseDir = System.getProperty("user.home") + "/.miraagent/files";
}
