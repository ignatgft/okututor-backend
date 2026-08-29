package com.okututor.backend.media;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaObjectRepository extends JpaRepository<MediaObject, UUID> {

    Optional<MediaObject> findFirstByKindAndOwner_IdOrderByCreatedAtDesc(MediaKind kind, UUID ownerId);

    Optional<MediaObject> findFirstByKindAndCourseIdOrderByCreatedAtDesc(MediaKind kind, UUID courseId);

    List<MediaObject> findByOwnerIsNullAndCourseIdIsNullAndCreatedAtBefore(java.time.Instant threshold);
}
