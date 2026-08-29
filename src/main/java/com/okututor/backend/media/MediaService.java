package com.okututor.backend.media;

import com.okututor.backend.common.config.AppProperties;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.course.Course;
import com.okututor.backend.course.CourseRepository;
import com.okututor.backend.course.CourseService;
import com.okututor.backend.user.User;
import java.util.Optional;
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

    private final ImageProcessor imageProcessor;
    private final ObjectStorage storage;
    private final MediaObjectRepository mediaObjects;
    private final CourseRepository courseRepository;
    private final MediaMetrics metrics;
    private final AppProperties properties;

    public MediaService(ImageProcessor imageProcessor,
                        ObjectStorage storage,
                        MediaObjectRepository mediaObjects,
                        CourseRepository courseRepository,
                        MediaMetrics metrics,
                        AppProperties properties) {
        this.imageProcessor = imageProcessor;
        this.storage = storage;
        this.mediaObjects = mediaObjects;
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

    private void cleanup(Optional<MediaObject> previous) {
        previous.ifPresent(old -> {
            mediaObjects.delete(old);
            deleteQuietly(old.getObjectKey());
        });
    }

    // ---------- внутренний pipeline ----------

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
            obj.setQuality(kind == MediaKind.AVATAR ? properties.getMedia().getAvatarQuality()
                    : kind == MediaKind.COURSE_COVER ? properties.getMedia().getCourseCoverQuality()
                    : properties.getMedia().getProfileQuality());
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
