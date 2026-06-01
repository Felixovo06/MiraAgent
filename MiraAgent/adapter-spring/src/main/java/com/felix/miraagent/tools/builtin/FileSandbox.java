package com.felix.miraagent.tools.builtin;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件工具沙箱：把相对路径解析到 base-dir 内，拒绝越界（路径穿越防护）。
 */
class FileSandbox {

    private final Path baseDir;

    FileSandbox(String baseDir) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
    }

    Path baseDir() {
        return baseDir;
    }

    /** 解析并校验：返回 base-dir 内的归一化绝对路径，越界抛 IllegalArgumentException。 */
    Path resolve(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        Path resolved = baseDir.resolve(relative).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("path escapes sandbox: " + relative);
        }
        return resolved;
    }

    void ensureBaseDir() throws IOException {
        java.nio.file.Files.createDirectories(baseDir);
    }
}
