package com.felix.miraagent.tools.builtin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSandboxTest {

    @Test
    void allowsPlainRelativePathInsideSandbox(@TempDir Path dir) {
        FileSandbox sandbox = new FileSandbox(dir.toString());
        Path p = sandbox.resolve("sub/notes.txt");
        assertTrue(p.startsWith(dir.toAbsolutePath().normalize()));
    }

    @Test
    void allowsNewFileWhoseLeafDoesNotExist(@TempDir Path dir) {
        FileSandbox sandbox = new FileSandbox(dir.toString());
        // 写新文件场景：叶子不存在也应放行（父目录是沙箱内真实目录）
        assertDoesNotThrow(() -> sandbox.resolve("brand-new.docx"));
    }

    @Test
    void rejectsDotDotTraversal(@TempDir Path dir) {
        FileSandbox sandbox = new FileSandbox(dir.toString());
        assertThrows(IllegalArgumentException.class, () -> sandbox.resolve("../../etc/passwd"));
    }

    @Test
    void rejectsAbsolutePathOutside(@TempDir Path dir) {
        FileSandbox sandbox = new FileSandbox(dir.toString());
        assertThrows(IllegalArgumentException.class, () -> sandbox.resolve("/etc/hosts"));
    }

    @Test
    void rejectsSymlinkPointingOutsideSandbox(@TempDir Path dir, @TempDir Path outside) throws IOException {
        // 沙箱内放一个指向外部文件的软链接 —— 词法检查看着在沙箱内，真实路径在外，必须拦截
        Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "top secret");
        Path link = dir.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (UnsupportedOperationException | IOException e) {
            return; // 文件系统不支持软链接则跳过
        }
        FileSandbox sandbox = new FileSandbox(dir.toString());
        assertThrows(IllegalArgumentException.class, () -> sandbox.resolve("link.txt"));
    }

    @Test
    void rejectsFileUnderSymlinkedDirectoryEscapingSandbox(@TempDir Path dir, @TempDir Path outside) throws IOException {
        // 沙箱内一个软链接目录指向外部，其下的新文件路径同样要拦截（写新文件 + 逃逸父目录）
        Path linkDir = dir.resolve("escape");
        try {
            Files.createSymbolicLink(linkDir, outside);
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        FileSandbox sandbox = new FileSandbox(dir.toString());
        assertThrows(IllegalArgumentException.class, () -> sandbox.resolve("escape/evil.txt"));
    }

    @Test
    void allowsSymlinkPointingInsideSandbox(@TempDir Path dir) throws IOException {
        // 指向沙箱内的软链接应放行
        Path real = dir.resolve("real.txt");
        Files.writeString(real, "ok");
        Path link = dir.resolve("alias.txt");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        FileSandbox sandbox = new FileSandbox(dir.toString());
        assertDoesNotThrow(() -> sandbox.resolve("alias.txt"));
    }
}
