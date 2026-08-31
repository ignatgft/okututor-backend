package com.okututor.backend.media;

import com.okututor.backend.common.config.AppProperties;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.course.Course;
import com.okututor.backend.course.CourseRepository;
import com.okututor.backend.course.CourseService;
import com.okututor.backend.user.User;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * приём -> оптимизация -> storage -> metadata. Оригинал не сохраняется.
 * Порядок replace (#38): upload нового объекта, затем DB, затем удаление
 * старого; сбой БД откатывает новый объект (#39), сбой удаления старого
 * не ломает запрос — объект подхватит orphan cleanup.
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    /** допустимые типы вложений сообщений (messenger + support). */
    private static final Set<String> ALLOWED_ATTACHMENT_MIME = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif",
            "application/pdf", "text/plain", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    /** расширение -> MIME для разрезов ``application/octet-stream`` от браузеров. */
    private static final Map<String, String> EXTENSION_MIME = Map.of(
            "jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png",
            "webp", "image/webp", "gif", "image/gif", "pdf", "application/pdf",
            "txt", "text/plain", "doc", "application/msword",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final ImageProcessor imageProcessor;
    private final ObjectStorage storage;
    private final MediaObjectRepository mediaObjects;
    private final MessageAttachmentRepository messageAttachments;
    private final CourseRepository courseRepository;
    private final MediaMetrics metrics;
    private final AppProperties properties;

    public MediaService(ImageProcessor imageProcessor,
                        ObjectStorage storage,
                        MediaObjectRepository mediaObjects,
                        MessageAttachmentRepository messageAttachments,
                        CourseRepository courseRepository,
                        MediaMetrics metrics,
                        AppProperties properties) {
        this.imageProcessor = imageProcessor;
        this.storage = storage;
        this.mediaObjects = mediaObjects;
        this.messageAttachments = messageAttachments;
        this.courseRepository = courseRepository;
        this.metrics = metrics;
        this.properties = properties;
    }

    /** загрузка/замена аватара пользователя. Возвращает публичный URL. */
    @Transactional
    public String updateAvatar(User user, MultipartFile file) {
        // предыдущий объект захватываем ДО сохранения нового:
        // иначе auto-flush вернёт сам новый объект и мы удалим его же файл
        Optional<MediaObject> previous =
                mediaObjects.findFirstByKindAndOwner_IdOrderByCreatedAtDesc(MediaKind.AVATAR, user.getId());
        byte[] input = readBytes(file);
        ProcessedImage processed = process(MediaKind.AVATAR, input);
        String key = MediaKind.AVATAR.objectKey(user.getId(), processed.extension());

        MediaObject saved = persistNew(user, null, MediaKind.AVATAR, key, processed);
        cleanup(previous);
        user.setAvatarUrl(saved.getPublicUrl());
        return saved.getPublicUrl();
    }

    /** загрузка обложки курса с проверкой владения (#36). Возвращает публичный URL. */
    @Transactional
    public String updateCourseCover(User actor, UUID courseId, MultipartFile file) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> ApiException.notFound("Course not found"));
        CourseService.requireOwnerOrAdmin(actor, course);

        Optional<MediaObject> previous =
                mediaObjects.findFirstByKindAndCourseIdOrderByCreatedAtDesc(MediaKind.COURSE_COVER, courseId);
        byte[] input = readBytes(file);
        ProcessedImage processed = process(MediaKind.COURSE_COVER, input);
        String key = MediaKind.COURSE_COVER.objectKey(course.getId(), processed.extension());

        MediaObject saved = persistNew(null, course, MediaKind.COURSE_COVER, key, processed);
        cleanup(previous);
        course.setCoverUrl(saved.getPublicUrl());
        return saved.getPublicUrl();
    }

    /** удаление аватара: DB + storage, без dangling references (#37). */
    @Transactional
    public void deleteAvatar(User user) {
        user.setAvatarUrl(null);
        mediaObjects.findFirstByKindAndOwner_IdOrderByCreatedAtDesc(MediaKind.AVATAR, user.getId())
                .ifPresent(old -> {
                    mediaObjects.delete(old);
                    deleteQuietly(old.getObjectKey());
                });
    }

    // ---------- вложения сообщений ----------

    /**
     * сохраняет файл как вложение (без привязки к сообщению). Двухшаговый flow:
     * POST /api/v1/messages/attachments -> media_id, затем send с attachment_media_id.
     * Изображения оптимизируются и получают миниатюру, документы хранятся как есть.
     */
    @Transactional
    public MessageAttachment storeMessageAttachment(User owner, MultipartFile file) {
        byte[] input = readBytes(file);
        String filename = sanitizeFilename(file.getOriginalFilename());
        String contentType = resolveContentType(file, filename);

        if (input.length > properties.getMedia().getMaxMessageAttachmentSize()) {
            throw ApiException.validation("File is too large. Max %d bytes"
                    .formatted(properties.getMedia().getMaxMessageAttachmentSize()));
        }
        return contentType.startsWith("image/")
                ? storeImageAttachment(owner, filename, contentType, input)
                : storeRawAttachment(owner, filename, contentType, input);
    }

    /** загрузка вложения + привязка к чату в одном запросе (multipart send). */
    @Transactional
    public MessageAttachment storeClaimedMessageAttachment(User owner, MultipartFile file) {
        MessageAttachment attachment = storeMessageAttachment(owner, file);
        attachment.setClaimedAt(java.time.Instant.now());
        return messageAttachments.save(attachment);
    }

    /** привязка ранее загруженного вложения по media_id (двухшаговый flow send). */
    @Transactional
    public MessageAttachment claimMessageAttachment(User owner, UUID mediaId) {
        MessageAttachment attachment = messageAttachments.findByMediaId(mediaId)
                .orElseThrow(() -> ApiException.notFound("Media not found"));
        if (attachment.isClaimed()) {
            throw ApiException.conflict("Attachment has already been used");
        }
        MediaObject media = attachment.getMedia();
        if (media.getOwner() == null || !media.getOwner().getId().equals(owner.getId())) {
            throw ApiException.forbidden("Not your attachment");
        }
        attachment.setClaimedAt(java.time.Instant.now());
        return messageAttachments.save(attachment);
    }

    private MessageAttachment storeImageAttachment(User owner, String filename,
                                                   String contentType, byte[] input) {
        List<String> uploaded = new ArrayList<>();
        try {
            if ("image/gif".equals(contentType)) {
                // анимацию сохраняем в оригинале, миниатюра — статичный WebP
                MediaObject media = persistRaw(owner, MediaKind.MESSAGE_ATTACHMENT, "image/gif", "gif", input);
                uploaded.add(media.getObjectKey());
                ProcessedImage thumb = process(MediaKind.MESSAGE_THUMBNAIL, input);
                MediaObject thumbnail = persistImage(owner, MediaKind.MESSAGE_THUMBNAIL, thumb);
                uploaded.add(thumbnail.getObjectKey());
                return saveAttachment(owner, media, thumbnail, filename, contentType,
                        input.length, AttachmentKind.IMAGE);
            }
            ProcessedImage main = process(MediaKind.MESSAGE_ATTACHMENT, input);
            MediaObject media = persistImage(owner, MediaKind.MESSAGE_ATTACHMENT, main);
            uploaded.add(media.getObjectKey());
            ProcessedImage thumb = process(MediaKind.MESSAGE_THUMBNAIL, input);
            MediaObject thumbnail = persistImage(owner, MediaKind.MESSAGE_THUMBNAIL, thumb);
            uploaded.add(thumbnail.getObjectKey());
            return saveAttachment(owner, media, thumbnail, filename, contentType,
                    main.data().length, AttachmentKind.IMAGE);
        } catch (RuntimeException e) {
            uploaded.forEach(this::deleteQuietly);
            throw e;
        }
    }

    private MessageAttachment storeRawAttachment(User owner, String filename,
                                                 String contentType, byte[] input) {
        List<String> uploaded = new ArrayList<>();
        try {
            String ext = extensionOf(filename);
            MediaObject media = persistRaw(owner, MediaKind.MESSAGE_ATTACHMENT, contentType, ext, input);
            uploaded.add(media.getObjectKey());
            return saveAttachment(owner, media, null, filename, contentType,
                    input.length, AttachmentKind.FILE);
        } catch (RuntimeException e) {
            uploaded.forEach(this::deleteQuietly);
            throw e;
        }
    }

    private MessageAttachment saveAttachment(User owner, MediaObject media, MediaObject thumbnail,
                                             String filename, String contentType, long size,
                                             AttachmentKind kind) {
        MessageAttachment attachment = new MessageAttachment();
        attachment.setMedia(media);
        attachment.setThumbnail(thumbnail);
        attachment.setOriginalFilename(filename);
        attachment.setContentType(contentType);
        attachment.setSizeBytes(size);
        attachment.setKind(kind);
        return messageAttachments.save(attachment);
    }

    // ---------- внутренний pipeline ----------

    private void cleanup(Optional<MediaObject> previous) {
        previous.ifPresent(old -> {
            mediaObjects.delete(old);
            deleteQuietly(old.getObjectKey());
        });
    }

    private MediaObject persistNew(User owner, Course course, MediaKind kind,
                                   String key, ProcessedImage processed) {
        long start = System.currentTimeMillis();
        ObjectStorage.StoredObject stored = safeUpload(kind, key, processed);
        metrics.r2UploadDuration(System.currentTimeMillis() - start);

        try {
            MediaObject obj = new MediaObject();
            obj.setOwner(owner);
            obj.setCourseId(course != null ? course.getId() : null);
            obj.setObjectKey(stored.key());
            obj.setPublicUrl(stored.publicUrl());
            obj.setKind(kind);
            obj.setMimeType(processed.contentType());
            obj.setFileSize(processed.data().length);
            obj.setWidth(processed.width());
            obj.setHeight(processed.height());
            obj.setFormat(processed.extension());
            obj.setQuality(qualityFor(kind));
            obj = mediaObjects.save(obj);

            log.info("media: {} {} -> {} bytes ({})", kind,
                    stored.key(), processed.data().length, processed.contentType());
            metrics.uploadSuccess(kind, processed.data().length, processed.data().length);
            return obj;
        } catch (RuntimeException e) {
            // R2 SUCCESS + DB FAILED -> удалить новый объект (#39)
            deleteQuietly(key);
            throw e;
        }
    }

    private ProcessedImage process(MediaKind kind, byte[] input) {
        var options = ImageProcessingOptions.forKind(properties.getMedia(), kind);
        try {
            ProcessedImage result = metrics.timedProcessing(kind, () -> imageProcessor.process(input, options));
            metrics.processingSuccess(kind);
            return result;
        } catch (RuntimeException e) {
            metrics.processingFailure(kind, e);
            throw MediaMetrics.wrapFailure(kind, e);
        }
    }

    private ObjectStorage.StoredObject safeUpload(MediaKind kind, String key, ProcessedImage processed) {
        try {
            return storage.upload(key, processed.data(), processed.contentType());
        } catch (RuntimeException e) {
            metrics.uploadFailure(kind);
            log.error("media: upload to storage failed for {}", key, e);
            throw new IllegalStateException("Storage is temporarily unavailable", e);
        }
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("media: deferred cleanup for orphan object {}: {}", key, e.getMessage());
        }
    }

    /** оптимизированное изображение (resize/кодирование) в storage + DB. */
    private MediaObject persistImage(User owner, MediaKind kind, ProcessedImage processed) {
        return persistNew(owner, null, kind, kind.objectKey(owner.getId(), processed.extension()), processed);
    }

    /** файл как есть (PDF/docx/txt/GIF): в storage идёт оригинал. */
    private MediaObject persistRaw(User owner, MediaKind kind, String mimeType, String ext, byte[] data) {
        String key = kind.objectKey(owner.getId(), ext == null ? "bin" : ext);
        long start = System.currentTimeMillis();
        ObjectStorage.StoredObject stored;
        try {
            stored = storage.upload(key, data, mimeType);
        } catch (RuntimeException e) {
            metrics.uploadFailure(kind);
            log.error("media: upload to storage failed for {}", key, e);
            throw new IllegalStateException("Storage is temporarily unavailable", e);
        }
        metrics.r2UploadDuration(System.currentTimeMillis() - start);

        try {
            MediaObject obj = new MediaObject();
            obj.setOwner(owner);
            obj.setObjectKey(stored.key());
            obj.setPublicUrl(stored.publicUrl());
            obj.setKind(kind);
            obj.setMimeType(mimeType);
            obj.setFileSize(data.length);
            obj.setFormat(ext);
            obj = mediaObjects.save(obj);
            log.info("media: {} {} -> {} bytes ({})", kind, stored.key(), data.length, mimeType);
            metrics.uploadSuccess(kind, data.length, data.length);
            return obj;
        } catch (RuntimeException e) {
            deleteQuietly(key);
            throw e;
        }
    }

    /** фактическое качество кодирования изображения, соответствует app.media.* */
    private int qualityFor(MediaKind kind) {
        return switch (kind) {
            case AVATAR -> properties.getMedia().getAvatarQuality();
            case COURSE_COVER -> properties.getMedia().getCourseCoverQuality();
            case PROFILE -> properties.getMedia().getProfileQuality();
            case MESSAGE_ATTACHMENT -> properties.getMedia().getMessageAttachmentQuality();
            case MESSAGE_THUMBNAIL -> properties.getMedia().getMessageThumbnailQuality();
        };
    }

    /**
     * определяет MIME вложения: сначала заявленный в multipart, при
     * ``application/octet-stream``/пустом — по расширению файла. Вне
     * whitelist — ошибка валидации (никогда не octet-stream далее).
     */
    private static String resolveContentType(MultipartFile file, String filename) {
        String declared = file.getContentType();
        String normalized = declared == null ? "" : declared.trim().toLowerCase(Locale.ROOT);
        if (ALLOWED_ATTACHMENT_MIME.contains(normalized)) {
            return normalized;
        }
        if (normalized.isBlank() || "application/octet-stream".equals(normalized)) {
            String ext = extensionOf(filename);
            String mapped = ext == null ? null : EXTENSION_MIME.get(ext);
            if (mapped != null) {
                return mapped;
            }
        }
        throw ApiException.validation("Unsupported file type");
    }

    /** чистит имя файла: путь -> имя, без управляющих символов, max 255. */
    private static String sanitizeFilename(String raw) {
        if (raw == null) {
            return "file";
        }
        String name = raw.replace('\\', '/');
        int cut = name.lastIndexOf('/');
        if (cut >= 0) {
            name = name.substring(cut + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) {
            return "file";
        }
        return name.length() > 250 ? name.substring(0, 250) : name;
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static byte[] readBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.validation("File is required");
        }
        try {
            return file.getBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read uploaded file", e);
        }
    }
}
