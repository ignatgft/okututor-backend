package com.okututor.backend.media;

import com.okututor.backend.common.error.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * метрики медиа-pipeline (#53): success/failure, длительность обработки,
 * исходные/оптимизированные байты, коэффициент сжатия.
 */
@Service
public class MediaMetrics {

    private static final Logger log = LoggerFactory.getLogger(MediaMetrics.class);

    private final MeterRegistry registry;
    private final Timer processingTimer;

    public MediaMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.processingTimer = Timer.builder("media.processing.duration")
                .description("image processing time")
                .register(registry);
    }

    public void uploadSuccess(MediaKind kind, long originalBytes, long optimizedBytes) {
        Counter.builder("media.upload.success").tag("type", kind.name()).register(registry).increment();
        registry.summary("media.original.bytes").record(originalBytes);
        registry.summary("media.optimized.bytes").record(optimizedBytes);
        if (originalBytes > 0) {
            registry.gauge("media.compression.ratio.last", (double) optimizedBytes / originalBytes);
        }
    }

    public void uploadFailure(MediaKind kind) {
        Counter.builder("media.upload.failure").tag("type", kind.name()).register(registry).increment();
    }

    public void processingSuccess(MediaKind kind) {
        Counter.builder("media.processing.success").tag("type", kind.name()).register(registry).increment();
    }

    public void processingFailure(MediaKind kind, Exception e) {
        Counter.builder("media.processing.failure")
                .tag("type", kind.name())
                .tag("reason", e.getClass().getSimpleName())
                .register(registry)
                .increment();
        log.warn("media: processing failed for {}: {}", kind, e.getMessage());
    }

    public void r2UploadDuration(long millis) {
        registry.timer("media.r2.upload.duration").record(millis, TimeUnit.MILLISECONDS);
    }

    public <T> T timedProcessing(MediaKind kind, java.util.function.Supplier<T> action) {
        return processingTimer.record(() -> action.get());
    }

    public static ApiException wrapFailure(MediaKind kind, Exception e) {
        return e instanceof ApiException api ? api
                : ApiException.validation("Image processing failed");
    }
}
