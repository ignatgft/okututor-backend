package com.okututor.backend.media;

/** результат обработки: готовые к upload байты + метрики. */
public record ProcessedImage(
        byte[] data,
        String contentType,
        String extension,
        int width,
        int height,
        boolean bypassed
) {}
