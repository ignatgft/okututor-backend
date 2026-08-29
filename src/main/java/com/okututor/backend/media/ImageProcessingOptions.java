package com.okututor.backend.media;

/**
 * параметры обработки одного изображения; собираются из app.media.*
 * по {@link MediaKind}, чтобы quality/размеры не хардкодились в сервисах.
 */
public record ImageProcessingOptions(
        int maxWidth,
        int maxHeight,
        CropMode cropMode,
        String format,
        int quality,
        boolean stripMetadata,
        long maxInputBytes
) {

    public static ImageProcessingOptions forKind(com.okututor.backend.common.config.AppProperties.Media cfg,
                                                 MediaKind kind) {
        return switch (kind) {
            case AVATAR -> new ImageProcessingOptions(cfg.getAvatarMaxWidth(), cfg.getAvatarMaxHeight(),
                    CropMode.CENTER_SQUARE, cfg.getFormat(), cfg.getAvatarQuality(), true, cfg.getMaxAvatarSize());
            case COURSE_COVER -> new ImageProcessingOptions(cfg.getCourseCoverMaxWidth(),
                    cfg.getCourseCoverMaxHeight(), CropMode.CENTER_ASPECT, cfg.getFormat(),
                    cfg.getCourseCoverQuality(), true, cfg.getMaxCourseCoverSize());
            case PROFILE -> new ImageProcessingOptions(cfg.getProfileMaxWidth(), cfg.getProfileMaxHeight(),
                    CropMode.FIT, cfg.getFormat(), cfg.getProfileQuality(), true, cfg.getMaxProfileSize());
        };
    }
}
