package com.okututor.backend.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.course.Course;
import com.okututor.backend.course.CourseRepository;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * защита от двойной брони: 100 параллельных попыток на один
 * слот учителя должен дать ровно один успех и 99 CONFLICT (409).
 * нужен Docker; в CI выполняется, локально без контейнеров пропускается.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class BookingConcurrencyIT {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine").withReuse(false);

    @Autowired
    BookingService bookingService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    CourseRepository courseRepository;

    private User persistedUser(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setVerified(true);
        user.setRole(role);
        return userRepository.save(user);
    }

    @Test
    void concurrentBookingsForSameSlotProduceSingleSuccess() throws Exception {
        User teacher = persistedUser("teacher-%s@test.com".formatted(UUID.randomUUID()), Role.TUTOR);
        User student = persistedUser("student-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);

        Course course = new Course();
        course.setTeacher(teacher);
        course.setTitle("Mathematics Basics");
        course.setSubject("Mathematics");
        course.setPricePerHour(BigDecimal.valueOf(1500));
        course.setStatus(Course.Status.APPROVED);
        final Course savedCourse = courseRepository.save(course);

        String futureDate = Instant.now().plusSeconds(3600 * 24 * 7).toString().substring(0, 10);
        String time = "10:00";

        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Throwable> conflicts = new CopyOnWriteArrayList<>();
        AtomicInteger successes = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    bookingService.create(student, savedCourse.getId(), null, futureDate, time, 60, null);
                    successes.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    if (t instanceof ApiException api && "CONFLICT".equals(api.getCode())) {
                        conflicts.add(t);
                    }
                }
            });
        }

        startLatch.countDown();
        await().atMost(30, TimeUnit.SECONDS).until(() ->
                successes.get() + conflicts.size() >= threads);
        pool.shutdownNow();

        assertThat(successes.get())
                .as("exactly one booking wins the slot")
                .isEqualTo(1);
        assertThat(conflicts)
                .as("all other attempts must be rejected with CONFLICT")
                .hasSize(threads - 1);
    }
}
