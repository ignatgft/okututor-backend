package com.okututor.backend.media;

import java.util.UUID;

/**
 * публичное представление вложения сообщения (используется в messenger
 * и support-чате). url — основной файл (для IMAGE — оптимизированная
 * WebP-копия либо оригинал GIF), thumbnail_url — только для изображений.
 */
public record MessageAttachmentRef(
        UUID media_id,
        String url,
        String thumbnail_url,
        String filename,
        String content_type,
        long size,
        String kind
) {

    public static MessageAttachmentRef of(MessageAttachment attachment) {
        MediaObject media = attachment.getMedia();
        MediaObject thumbnail = attachment.getThumbnail();
        return new MessageAttachmentRef(
                media.getId(),
                media.getPublicUrl(),
                thumbnail != null ? thumbnail.getPublicUrl() : null,
                attachment.getOriginalFilename(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                attachment.getKind().name());
    }
}