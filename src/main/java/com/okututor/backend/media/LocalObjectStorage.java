package com.okututor.backend.media;

import com.okututor.backend.common.config.AppProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * dev/local реализация: файлы в {app.storage.local-dir}/media/{key}.
 * Раздаются контроллером GET /api/v1/files/media/{key}.
 */
@Component
@ConditionalOnProperty(prefix = "app.media", name = "provider", havingValue = "local", matchIfMissing = true)
public class LocalObjectStorage implements ObjectStorage {

    private final Path baseDir;
    private final String publicBaseUrl;

    public LocalObjectStorage(AppProperties properties) {
        this.baseDir = Path.of(properties.getStorage().getLocalDir()).toAbsolutePath().normalize().resolve("media");
        this.publicBaseUrl = properties.getStorage().getPublicBaseUrl() + "/media";
    }

    @Override
    public StoredObject upload(String key, byte[] data, String contentType) {
        try {
            Path target = resolve(key);
            Files.createDirectories(target.getParent());
            Files.write(target, data);
            return new StoredObject(key, publicUrl(key), data.length);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store media object: " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete media object: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    /** байты объекта для раздачи контроллером. */
    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read media object: " + key, e);
        }
    }

    private Path resolve(String key) {
        // path traversal guard: канонизируем и требуем остаться внутри baseDir
        Path resolved = baseDir.resolve(key).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("Invalid media key");
        }
        return resolved;
    }

    @Override
    public String publicUrl(String key) {
        return publicBaseUrl + "/" + key;
    }
}
