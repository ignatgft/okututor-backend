package com.okututor.backend.media;

import com.okututor.backend.common.error.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * раздача локально сохранённых media-объектов (provider=local).
 * Для provider=r2 файлы отдаются Cloudflare CDN напрямую, этот
 * контроллер байты не проксирует (#32).
 */
@RestController
@ConditionalOnBean(LocalObjectStorage.class)
public class MediaFileController {

    private final LocalObjectStorage storage;

    public MediaFileController(LocalObjectStorage storage) {
        this.storage = storage;
    }

    @GetMapping("/api/v1/files/media/{*key}")
    public ResponseEntity<byte[]> read(@PathVariable String key) {
        String normalized = key.startsWith("/") ? key.substring(1) : key;
        byte[] data;
        try {
            data = storage.read(normalized);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw ApiException.notFound("File not found");
        }
        if (!storage.exists(normalized)) {
            throw ApiException.notFound("File not found");
        }
        return ResponseEntity.ok()
                .contentType(contentTypeOf(normalized))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic().immutable())
                .body(data);
    }

    private static MediaType contentTypeOf(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".pdf")) return MediaType.parseMediaType("application/pdf");
        if (lower.endsWith(".doc")) return MediaType.parseMediaType("application/msword");
        if (lower.endsWith(".docx")) return MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        if (lower.endsWith(".txt")) return MediaType.TEXT_PLAIN;
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
