package com.okututor.backend.schedule;

import com.okututor.backend.enrollment.Enrollment;
import com.okututor.backend.enrollment.EnrollmentRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Автоматический перевод заявок без ответа студента в EXPIRED (spec §31).
 * Заявка живёт DEFAULT_EXPIRATION дней и автоматически «протухает»,
 * если студент не ответил тьютору (или тьютор — студенту). Batch-обновление,
 * без загрузки строк в память.
 */
@Component
public class ApplicationExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ApplicationExpiryScheduler.class);

    private final EnrollmentRepository enrollmentRepository;

    public ApplicationExpiryScheduler(EnrollmentRepository enrollmentRepository) {
        this.enrollmentRepository = enrollmentRepository;
    }

    @Scheduled(fixedDelay = 15L * 60 * 1000, initialDelay = 60_000)
    @Transactional
    public void expireStale() {
        int updated = enrollmentRepository.expireStale(
                List.of(Enrollment.Status.PENDING, Enrollment.Status.NEEDS_INFO), Instant.now());
        if (updated > 0) {
            log.info("expired {} stale applications", updated);
        }
    }
}