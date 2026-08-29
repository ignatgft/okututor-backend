package com.okututor.backend.media;

/** тип медиа-объекта: определяет pipeline оптимизации и ключ в storage. */
public enum MediaKind {
    AVATAR("users", "avatar"),
    COURSE_COVER("courses", "cover"),
    PROFILE("tutors", "profile");

    private final String keyPrefix;
    private final String keySegment;

    MediaKind(String keyPrefix, String keySegment) {
        this.keyPrefix = keyPrefix;
        this.keySegment = keySegment;
    }

    /** users/{ownerId}/avatar/{uuid}.webp и т.п. */
    public String objectKey(java.util.UUID ownerId, String extension) {
        return "%s/%s/%s/%s.%s".formatted(keyPrefix, ownerId, keySegment,
                java.util.UUID.randomUUID(), extension);
    }
}
