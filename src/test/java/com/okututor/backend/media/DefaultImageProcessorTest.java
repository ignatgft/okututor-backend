package com.okututor.backend.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.okututor.backend.common.config.AppProperties;
import com.okututor.backend.common.error.ApiException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * проверки pipeline: формат выхода, размеры, сжатие, alpha, image bomb guard,
 * отказ по мусорным данным. Тестовые изображения генерируются на лету.
 */
class DefaultImageProcessorTest {

    private DefaultImageProcessor processor;
    private AppProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        processor = new DefaultImageProcessor(properties);
    }

    private ImageProcessingOptions avatarOptions() {
        return ImageProcessingOptions.forKind(properties.getMedia(), MediaKind.AVATAR);
    }

    private ImageProcessingOptions profileOptions() {
        return ImageProcessingOptions.forKind(properties.getMedia(), MediaKind.PROFILE);
    }

    /** фотореалистичный градиент + шум: хорошо жмётся и детерминирован. */
    private byte[] jpegBytes(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int r = (x * 255) / Math.max(w - 1, 1);
                int b = (y * 255) / Math.max(h - 1, 1);
                g.setColor(new Color(r, (x + y) % 256, b));
                g.fillRect(x, y, 1, 1);
            }
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private byte[] pngWithAlpha(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(200, 30, 30, 180));
        g.fillOval(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void bigJpegBecomesWebpAvatarWithinLimitsAndSmaller() throws IOException {
        byte[] original = jpegBytes(3000, 2000);

        ProcessedImage result = processor.process(original, avatarOptions());

        assertThat(result.contentType()).isEqualTo("image/webp");
        assertThat(result.width()).isLessThanOrEqualTo(512);
        assertThat(result.height()).isLessThanOrEqualTo(512);
        // выход валиден как изображение
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.data()));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(result.width());
        // оптимизация реально уменьшила файл
        assertThat(result.data().length).isLessThan(original.length);
    }

    @Test
    void pngAlphaPreservedThroughPipeline() throws IOException {
        byte[] png = pngWithAlpha(800, 600);

        ProcessedImage result = processor.process(png, avatarOptions());

        assertThat(result.extension()).isEqualTo("webp");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.data()));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getColorModel().hasAlpha()).isTrue();
    }

    @Test
    void fitModeDoesNotUpscaleSmallImages() throws IOException {
        byte[] small = jpegBytes(100, 80);

        ProcessedImage result = processor.process(small, profileOptions());

        assertThat(result.width()).isEqualTo(100);
        assertThat(result.height()).isEqualTo(80);
    }

    @Test
    void garbageBytesRejectedAsValidationError() {
        byte[] garbage = "this is definitely not an image".getBytes();

        assertThatThrownBy(() -> processor.process(garbage, avatarOptions()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void oversizedFileRejectedBeforeProcessing() {
        var options = new ImageProcessingOptions(512, 512, CropMode.CENTER_SQUARE,
                "webp", 82, true, 10L); // лимит 10 байт

        assertThatThrownBy(() -> processor.process(jpegBytesSafe(), options))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void pixelBombGuardRejectsHugeDimensionsViaBudget() throws IOException {
        // 300x300 картинка при бюджете 100 пикселей — guard срабатывает до декода
        properties.getMedia().setMaxPixels(100L);
        byte[] image = jpegBytes(300, 300);

        assertThatThrownBy(() -> processor.process(image, avatarOptions()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("dimensions");
    }

    private byte[] jpegBytesSafe() {
        try {
            return jpegBytes(64, 64);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void webpOutputIsReadableByRegisteredReaders() throws IOException {
        byte[] original = jpegBytes(1024, 768);
        ProcessedImage result = processor.process(original, avatarOptions());

        Iterator<javax.imageio.ImageReader> readers =
                ImageIO.getImageReaders(new MemoryCacheImageInputStream(
                        new ByteArrayInputStream(result.data())));
        assertThat(readers.hasNext()).isTrue();
        String format = readers.next().getFormatName().toLowerCase(java.util.Locale.ROOT);
        assertThat(format).isEqualTo("webp");
    }
}
