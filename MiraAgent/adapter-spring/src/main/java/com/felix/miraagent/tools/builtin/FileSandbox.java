package com.felix.miraagent.tools.builtin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件工具沙箱：把相对路径解析到 base-dir 内，拒绝任何越界。
 * 文件/文档工具与上传 API 共享同一套防护——所有受模型/用户控制的文件操作都必须经此解析。
 *
 * <p>三层防护：
 * <ol>
 *   <li>词法层：相对路径 resolve 到 base-dir 后 normalize，拒绝 {@code ../} 穿越与绝对路径越界；</li>
 *   <li>真实路径层：对已存在的最近祖先做 {@link Path#toRealPath()}（解析符号链接），
 *       确保真实目标仍在沙箱内——防御「沙箱内的软链接指向外部」这类逃逸；</li>
 *   <li>写新文件场景：叶子不存在时，校验其最近的已存在祖先（父目录可能是逃逸软链接）。</li>
 * </ol>
 */
public class FileSandbox {

    private final Path baseDir;

    public FileSandbox(String baseDir) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
    }

    public Path baseDir() {
        return baseDir;
    }

    /**
     * 解析并校验：返回 base-dir 内的归一化绝对路径。
     * 任何越界（穿越/绝对路径/符号链接逃逸/解析失败）一律抛 {@link IllegalArgumentException}。
     */
    public Path resolve(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        Path resolved = baseDir.resolve(relative).normalize();
        // 1) 词法防护：挡住 ../ 与绝对路径越界
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("path escapes sandbox: " + relative);
        }
        // 2/3) 真实路径防护：解析符号链接，校验真实目标仍在沙箱内
        try {
            Path realBase = realBaseDir();
            Path real = firstExisting(resolved).toRealPath();
            if (!real.startsWith(realBase)) {
                throw new IllegalArgumentException("path escapes sandbox (symlink): " + relative);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("path resolve failed: " + relative);
        }
        return resolved;
    }

    public void ensureBaseDir() throws IOException {
        Files.createDirectories(baseDir);
    }

    /** 规范化的沙箱根（解析自身路径上的符号链接，如 macOS 的 /tmp → /private/tmp）。 */
    private Path realBaseDir() throws IOException {
        Files.createDirectories(baseDir);
        return baseDir.toRealPath();
    }

    /** 从给定路径向上找到第一个真实存在的祖先（写新文件时叶子不存在，校验其父）。 */
    private Path firstExisting(Path p) {
        Path cur = p;
        while (cur != null && !Files.exists(cur)) {
            cur = cur.getParent();
        }
        // baseDir 已被 realBaseDir() 创建，至少 base 本身存在，cur 不会为 null
        return cur != null ? cur : baseDir;
    }
}
