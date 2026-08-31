package com.okututor.backend.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.okututor.backend.common.config.AppProperties;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.course.CourseRepository;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

/**
 * порядок replace (#38/#39): сбой БД откатывает новый объект,
 * старый удаляется после сохранения, deleteAvatar чистит DB+storage.
 */
class MediaServiceTest {

    private ImageProcessor processor;
    private ObjectStorage storage;
    private MediaObjectRepository mediaObjects;
    private MessageAttachmentRepository messageAttachments;
    private CourseRepository courseRepository;
    private MediaMetrics metrics;
    private AppProperties properties;
    private MediaService service;

    private User user;
    private MultipartFile file;
    private ProcessedImage processed;

    @BeforeEach
    void setUp() {
        processor = mock(ImageProcessor.class);
        storage = mock(ObjectStorage.class);
        mediaObjects = mock(MediaObjectRepository.class);
        messageAttachments = mock(MessageAttachmentRepository.class);
        courseRepository = mock(CourseRepository.class);
        metrics = new MediaMetrics(new SimpleMeterRegistry());
        properties = new AppProperties();
        service = new MediaService(processor, storage, mediaObjects, messageAttachments, courseRepository, metrics, properties);

        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("u@test.com");
        user.setRole(Role.STUDENT);

        file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        try {
            when(file.getBytes()).thenReturn(new byte[] {1, 2, 3});
        } catch (Exception ignored) {
        }

        processed = new ProcessedImage(new byte[] {9, 9, 9}, "image/webp", "webp", 512, 512, false);
        when(processor.process(any(), any())).thenReturn(processed);
        when(storage.upload(anyString(), any(), anyString()))
                .thenAnswer(inv -> new ObjectStorage.StoredObject(
                        inv.getArgument(0), "/media/" + inv.getArgument(0), 3L));
        when(mediaObjects.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void avatarReplaceUploadsSavesThenDeletesOldObject() {
        MediaObject oldObject = new MediaObject();
        oldObject.setObjectKey("users/%s/avatar/old.webp".formatted(user.getId()));
        when(mediaObjects.findFirstByKindAndOwner_IdOrderByCreatedAtDesc(MediaKind.AVATAR, user.getId()))
                .thenReturn(Optional.of(oldObject));

        String url = service.updateAvatar(user, file);

        var inOrder = org.mockito.Mockito.inOrder(storage, mediaObjects);
        inOrder.verify(storage).upload(anyString(), eq(processed.data()), eq("image/webp"));
        inOrder.verify(mediaObjects).save(any());
        inOrder.verify(storage).delete(oldObject.getObjectKey());
        assertThat(user.getAvatarUrl()).isEqualTo(url);
    }

    @Test
    void dbFailureRemovesJustUploadedObject() {
        when(mediaObjects.findFirstByKindAndOwner_IdOrderByCreatedAtDesc(any(), any()))
                .thenReturn(Optional.empty());
        when(mediaObjects.save(any())).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.updateAvatar(user, file))
                .isInstanceOf(IllegalStateException.class);

        // новый объект удалён из storage, аватар не изменился
        verify(storage).upload(anyString(), any(), anyString());
        verify(storage).delete(anyString());
        assertThat(user.getAvatarUrl()).isNull();
    }

    @Test
    void storageFailurePropagatesWithoutDbWrites() {
        when(storage.upload(anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("r2 down"));

        assertThatThrownBy(() -> service.updateAvatar(user, file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Storage");

        verify(mediaObjects, never()).save(any());
        verify(storage, never()).delete(anyString());
    }

    @Test
    void deleteAvatarClearsUrlAndRemovesObject() {
        MediaObject existing = new MediaObject();
        existing.setObjectKey("users/%s/avatar/old.webp".formatted(user.getId()));
        user.setAvatarUrl("/media/x");
        when(mediaObjects.findFirstByKindAndOwner_IdOrderByCreatedAtDesc(MediaKind.AVATAR, user.getId()))
                .thenReturn(Optional.of(existing));

        service.deleteAvatar(user);

        verify(mediaObjects).delete(existing);
        verify(storage).delete(existing.getObjectKey());
        assertThat(user.getAvatarUrl()).isNull();
    }

    @Test
    void emptyFileRejected() {
        when(file.isEmpty()).thenReturn(true);

        assertThatThrownBy(() -> service.updateAvatar(user, file))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("required");
    }
}
