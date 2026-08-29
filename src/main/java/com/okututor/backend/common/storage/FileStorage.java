package com.okututor.backend.common.storage;

import com.okututor.backend.common.config.AppProperties;
import com.okututor.backend.common.error.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** локально-дисковое хранилище аватаров; позже заменяется на S3/MinIO. */
@Service
public class FileStorage {

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private final AppProperties properties;

    public FileStorage(AppProperties properties) {
        this.properties = properties;
    }

    public String storeImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.validation("File is required");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_IMAGE_TYPES.contains(contentType)) {
            throw ApiException.validation("Only JPEG/PNG/WebP/GIF images are allowed");
        }
        try {
            Path dir = Path.of(properties.getStorage().getLocalDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String extension = extensionOf(file.getOriginalFilename(), contentType);
            String storedName = UUID.randomUUID() + extension;
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, dir.resolve(storedName), StandardCopyOption.REPLACE_EXISTING);
            }
            return properties.getStorage().getPublicBaseUrl() + "/" + storedName;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store file", e);
        }
    }

    public byte[] read(String publicUrl) {
        String name = publicUrl.substring(publicUrl.lastIndexOf('/') + 1);
        if (name.isBlank()) {
            throw ApiException.notFound("File not found");
        }
        // защита от path traversal: канонизируем путь и требуем, чтобы он
        // оставался внутри localDir (".." / абсолютные пути / symlink-выход)
        Path baseDir = Path.of(properties.getStorage().getLocalDir()).toAbsolutePath().normalize();
        Path path = baseDir.resolve(name).normalize();
        if (!path.startsWith(baseDir)) {
            throw ApiException.notFound("File not found");
        }
        if (!Files.exists(path)) {
            throw ApiException.notFound("File not found");
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read file", e);
        }
    }

    private static String extensionOf(String originalName, String contentType) {
        if (originalName != null && originalName.contains(".")) {
            return originalName.substring(originalName.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        }
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".jpg";
        };
    }
}
