package com.okututor.backend.media;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * вложение сообщения (messenger + support-чат). Сам файл — в object storage
 * ({@link MediaObject}), здесь — контекст сообщения: исходное имя, тип,
 * размер и классификация IMAGE/FILE. Для картинок дополнительно хранится
 * миниатюра (thumbnail_media_id).
 */
@Entity
@Table(name = "message_attachments")
public class MessageAttachment {

    @Id
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "media_id", nullable = false)
    private MediaObject media;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "thumbnail_media_id")
    private MediaObject thumbnail;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AttachmentKind kind;

    /** момент привязки к сообщению (двухшаговый flow upload->send). */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public MediaObject getMedia() { return media; }
    public void setMedia(MediaObject media) { this.media = media; }
    public MediaObject getThumbnail() { return thumbnail; }
    public void setThumbnail(MediaObject thumbnail) { this.thumbnail = thumbnail; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long v) { this.sizeBytes = v; }
    public AttachmentKind getKind() { return kind; }
    public void setKind(AttachmentKind kind) { this.kind = kind; }
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
    public boolean isClaimed() { return claimedAt != null; }
    public Instant getCreatedAt() { return createdAt; }
}