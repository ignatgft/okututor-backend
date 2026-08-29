package com.okututor.backend.media;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.okututor.backend.common.config.AppProperties;
import com.okututor.backend.common.error.ApiException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.geometry.Positions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * единый pipeline обработки пользовательских изображений:
 * validate -> decode (pixel bomb guard) -> EXIF orientation -> crop ->
 * resize (high-quality) -> strip metadata (ре-энкод) -> WebP encode.
 *
 * Оригинал наружу не возвращается, кроме случая, когда вход уже WebP
 * в допустимых рамках, а «оптимизация» вышла тяжелее оригинала (#23 спеки).
 */
@Component
public class DefaultImageProcessor implements ImageProcessor {

    private static final Logger log = LoggerFactory.getLogger(DefaultImageProcessor.class);

    private final AppProperties properties;

    public DefaultImageProcessor(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public ProcessedImage process(byte[] input, ImageProcessingOptions options) {
        if (input == null || input.length == 0) {
            throw ApiException.validation("File is required");
        }
        if (input.length > options.maxInputBytes()) {
            throw ApiException.validation("File is too large. Max %d bytes".formatted(options.maxInputBytes()));
        }

        Dimensions dims = probeDimensions(input);
        validatePixelBudget(dims);

        BufferedImage decoded = decode(input);
        decoded = applyExifOrientation(decoded, originalOrientation(input));

        boolean hasAlpha = decoded.getColorModel().hasAlpha();
        BufferedImage normalized = normalizeColorModel(decoded, hasAlpha);
        BufferedImage scaled = resize(normalized, options, hasAlpha);

        byte[] encoded = encode(scaled, options.format(), options.quality(), hasAlpha);

        // защита от аномального результата: не хранить «оптимизацию», которая
        // тяжелее исходника; если вход уже webp и укладывается в лимиты — берём его
        if (encoded.length > input.length && dimsWithinLimits(dims, options)
                && "webp".equals(dims.formatName())) {
            log.info("media: optimized output {} bytes exceeds original {}; bypass re-encode",
                    encoded.length, input.length);
            return new ProcessedImage(input, "image/webp", "webp", dims.width(), dims.height(), true);
        }

        return new ProcessedImage(encoded,
                "webp".equals(options.format()) ? "image/webp" : "application/octet-stream",
                options.format(), scaled.getWidth(), scaled.getHeight(), false);
    }

    // ---------- guard / probe ----------

    private record Dimensions(int width, int height, String formatName) {}

    /** читает размеры из заголовка БЕЗ декодирования пикселей (image bomb protection). */
    private Dimensions probeDimensions(byte[] input) {
        Iterator<ImageReader> it = ImageIO.getImageReaders(
                new MemoryCacheImageInputStream(new ByteArrayInputStream(input)));
        if (!it.hasNext()) {
            throw ApiException.validation("Unsupported or corrupted image");
        }
        ImageReader reader = it.next();
        try {
            String formatName = reader.getFormatName().toLowerCase(Locale.ROOT);
            try (var in = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0) {
                    throw ApiException.validation("Invalid image dimensions");
                }
                return new Dimensions(w, h, formatName);
            } catch (IOException e) {
                throw ApiException.validation("Corrupted image");
            }
        } catch (IOException e) {
            throw ApiException.validation("Corrupted image");
        } finally {
            reader.dispose();
        }
    }

    private void validatePixelBudget(Dimensions dims) {
        var cfg = properties.getMedia();
        long pixels = (long) dims.width() * dims.height();
        if (pixels > cfg.getMaxPixels() || dims.width() > cfg.getMaxDimension()
                || dims.height() > cfg.getMaxDimension()) {
            throw ApiException.validation("Image dimensions are too large");
        }
    }

    private boolean dimsWithinLimits(Dimensions dims, ImageProcessingOptions o) {
        return dims.width() <= o.maxWidth() && dims.height() <= o.maxHeight();
    }

    private int originalOrientation(byte[] input) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(input));
            for (ExifIFD0Directory dir : metadata.getDirectoriesOfType(ExifIFD0Directory.class)) {
                Integer tag = dir.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
                if (tag != null) {
                    return tag;
                }
            }
        } catch (Exception ignored) {
            // нет EXIF (PNG/WebP и т.п.) — норма
        }
        return 1;
    }

    // ---------- pipeline steps ----------

    private BufferedImage decode(byte[] input) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(input));
            if (img == null) {
                throw ApiException.validation("Unsupported or corrupted image");
            }
            return img;
        } catch (IOException e) {
            throw ApiException.validation("Corrupted image");
        }
    }

    /**
     * физически применяет EXIF Orientation (1..8), чтобы аватар не «поворачивался»
     * после обработки; ре-энкод заодно стирает metadata (EXIF/GPS/device).
     */
    private BufferedImage applyExifOrientation(BufferedImage image, int orientation) {
        if (orientation <= 1) {
            return image;
        }
        AffineTransform tx = new AffineTransform();
        switch (orientation) {
            case 2 -> tx.scale(-1, 1);                                                        // flip H
            case 3 -> { tx.translate(image.getWidth(), image.getHeight()); tx.rotate(Math.PI); }
            case 4 -> { tx.translate(0, image.getHeight()); tx.scale(1, -1); }                // flip V
            case 5 -> { tx.rotate(Math.PI / 2); tx.scale(1, -1); }                            // transpose
            case 6 -> { tx.translate(image.getHeight(), 0); tx.rotate(Math.PI / 2); }         // 90 CW
            case 7 -> { tx.translate(image.getHeight(), image.getWidth());
                        tx.rotate(Math.PI / 2); tx.scale(-1, 1); }                            // transverse
            case 8 -> { tx.translate(0, image.getWidth()); tx.rotate(-Math.PI / 2); }         // 90 CCW
            default -> { return image; }
        }
        boolean swapSides = orientation >= 5 && orientation <= 8;
        int targetW = swapSides ? image.getHeight() : image.getWidth();
        int targetH = swapSides ? image.getWidth() : image.getHeight();
        BufferedImage oriented = new BufferedImage(targetW, targetH,
                image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = oriented.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, tx, null);
        g.dispose();
        return oriented;
    }

    private BufferedImage resize(BufferedImage src, ImageProcessingOptions o, boolean hasAlpha) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();

        var thumb = Thumbnails.of(src);
        switch (o.cropMode()) {
            // crop-to-fill ровно под целевые пропорции, high-quality downsampling
            case CENTER_SQUARE, CENTER_ASPECT ->
                    thumb.size(o.maxWidth(), o.maxHeight()).crop(Positions.CENTER);
            case FIT -> {
                double scale = Math.min(1.0,
                        Math.min((double) o.maxWidth() / srcW, (double) o.maxHeight() / srcH));
                if (scale < 1.0) {
                    thumb.size((int) Math.round(srcW * scale), (int) Math.round(srcH * scale));
                } else {
                    return normalizeColorModel(src, hasAlpha); // маленькие не увеличиваем
                }
            }
        }
        try {
            BufferedImage out = thumb.outputQuality(1.0).asBufferedImage(); // quality контролируем при кодировании
            return normalizeColorModel(out, hasAlpha);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to resize image", e);
        }
    }

    private BufferedImage normalizeColorModel(BufferedImage img, boolean hasAlpha) {
        int targetType = hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        if (img.getType() == targetType) {
            return img;
        }
        BufferedImage copy = new BufferedImage(img.getWidth(), img.getHeight(), targetType);
        Graphics2D g = copy.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return copy;
    }

    /** кодирование в целевой формат с настраиваемым качеством (WebP через webp-imageio). */
    private byte[] encode(BufferedImage image, String format, int quality, boolean hasAlpha) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType(mimeOf(format));
        if (!writers.hasNext()) {
            log.warn("media: no ImageIO writer for '{}'; falling back to {}",
                    format, hasAlpha ? "png" : "jpeg");
            return encodeFallback(image, out);
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            try {
                Class<?> webpParamClass = Class.forName("com.luciad.imageio.webp.WebPWriteParam");
                Object webpParam = webpParamClass.getConstructor(java.util.Locale.class)
                        .newInstance(writer.getLocale());
                webpParamClass.getMethod("setCompressionMode", int.class)
                        .invoke(webpParam, ImageWriteParam.MODE_EXPLICIT);
                webpParamClass.getMethod("setCompressionType", String.class).invoke(webpParam, "Lossy");
                webpParamClass.getMethod("setCompressionQuality", float.class)
                        .invoke(webpParam, quality / 100f);
                param = (ImageWriteParam) webpParam;
            } catch (ClassNotFoundException e) {
                if ("jpg".equalsIgnoreCase(format) && param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionType(param.getCompressionTypes()[0]);
                    param.setCompressionQuality(Math.min(quality, 95) / 100f);
                }
            }
            try (var imgOut = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(imgOut);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return out.toByteArray();
        } catch (IOException | ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to encode image", e);
        } finally {
            writer.dispose();
        }
    }

    private byte[] encodeFallback(BufferedImage image, ByteArrayOutputStream out) {
        try {
            ImageIO.write(image, image.getColorModel().hasAlpha() ? "png" : "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode fallback image", e);
        }
    }

    private static String mimeOf(String format) {
        return switch (format == null ? "" : format.toLowerCase(Locale.ROOT)) {
            case "png" -> "image/png";
            case "jpeg", "jpg" -> "image/jpeg";
            default -> "image/webp";
        };
    }
}
