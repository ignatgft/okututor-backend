package com.okututor.backend.media;

/** абстракция object storage; реализации: локальный диск и Cloudflare R2. */
public interface ObjectStorage {

    /** загружает объект с immutable cache-заголовками (ключ содержит UUID). */
    StoredObject upload(String key, byte[] data, String contentType);

    void delete(String key);

    boolean exists(String key);

    /** публичный URL объекта (CDN для R2, локальный эндпоинт для dev). */
    String publicUrl(String key);

    record StoredObject(String key, String publicUrl, long size) {}
}
