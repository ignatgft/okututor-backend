package com.okututor.backend.media;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageAttachmentRepository extends JpaRepository<MessageAttachment, UUID> {

    boolean existsByMediaId(UUID mediaId);

    Optional<MessageAttachment> findByMediaId(UUID mediaId);
}