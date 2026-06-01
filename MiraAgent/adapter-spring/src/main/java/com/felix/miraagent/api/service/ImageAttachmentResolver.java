package com.felix.miraagent.api.service;

import com.felix.miraagent.tools.builtin.FileSandbox;
import com.felix.miraagent.tools.builtin.FileToolProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把工作区里的图片文件名解析成可内联给模型的 data URL（base64）。
 * 仅处理图片扩展名；非图片（文档）忽略——文档走 document_read 工具，不内联。
 */
@Component
public class ImageAttachmentResolver {

    private static final Logger log = LoggerFactory.getLogger(ImageAttachmentResolver.class);
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024; // 单图上限 8MB

    private static final Map<String, String> MIME = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp",
            "bmp", "image/bmp");

    private final FileSandbox sandbox;

    public ImageAttachmentResolver(FileToolProperties properties) {
        this.sandbox = new FileSandbox(properties.getBaseDir());
    }

    /** 返回各图片的 data URL；无法解析/非图片/过大的条目跳过。 */
    public List<String> resolve(List<String> filenames) {
        if (filenames == null || filenames.isEmpty()) {
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        for (String name : filenames) {
            String mime = mimeOf(name);
            if (mime == null) {
                continue; // 非图片，跳过
            }
            try {
                Path file = sandbox.resolve(name);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                if (Files.size(file) > MAX_IMAGE_BYTES) {
                    log.warn("Image '{}' exceeds {} bytes, skipped", name, MAX_IMAGE_BYTES);
                    continue;
                }
                String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
                urls.add("data:" + mime + ";base64," + b64);
            } catch (Exception e) {
                log.warn("Failed to inline image '{}': {}", name, e.getMessage());
            }
        }
        return urls;
    }

    private static String mimeOf(String name) {
        if (name == null) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return MIME.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
