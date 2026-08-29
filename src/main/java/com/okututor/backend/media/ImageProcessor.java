package com.okututor.backend.media;

import com.okututor.backend.common.error.ApiException;

/** контракт оптимизации: бизнес-код не знает деталей сжатия. */
public interface ImageProcessor {

    /**
     * декодирует, применяет EXIF orientation, кадрирует, уменьшает,
     * удаляет metadata и кодирует в целевой формат.
     *
     * @throws ApiException если данные не изображение или нарушены лимиты
     */
    ProcessedImage process(byte[] input, ImageProcessingOptions options);
}
