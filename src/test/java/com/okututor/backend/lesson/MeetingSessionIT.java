package com.okututor.backend.lesson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.okututor.backend.booking.Booking;
import com.okututor.backend.booking.BookingRepository;
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
 * Жизненный цикл MeetingSession для одной booking:
 * первый запрос создаёт сессию, повторные переиспользуют её, параллельные
 * запросы не создают дублей, security/ownership и статус брони проверяются.
 * Требует Docker (mvn verify).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class MeetingSessionIT {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine").withReuse(false);

    @Autowired UserRepository userRepository;
    @Autowired CourseRepository courseRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired MeetingService meetingService;
    @Autowired MeetingSessionRepository meetingSessionRepository;

    private User persisted(String email, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setVerified(true);
        u.setRole(role);
        return userRepository.save(u);
    }

    private Course approvedCourse(User teacher) {
        Course c = new Course();
        c.setTeacher(teacher);
        c.setTitle("Meeting Algebra");
        c.setSubject("Algebra");
        c.setPricePerHour(BigDecimal.valueOf(800));
        c.setStatus(Course.Status.APPROVED);
        return courseRepository.save(c);
    }

    /** бронь CONFIRMED в ближайшем будущем — теперь внутри JOIN-окна [start-15m, end+60m]. */
    private Booking confirmedBooking(Course course, User student, User teacher) {
        Instant start = Instant.now().plusSeconds(5 * 60);
        Booking b = new Booking();
        b.setCourse(course);
        b.setStudent(student);
        b.setTeacher(teacher);
        b.setStartAt(start);
        b.setEndAt(start.plusSeconds(60 * 60));
        b.setDurationMinutes(60);
        b.setStatus(Booking.Status.CONFIRMED);
        return bookingRepository.save(b);
    }

    @Test
    void firstRequestCreatesSessionSecondRequestReusesIt() throws Exception {
        User teacher = persisted("mt1-t-%s@test.com".formatted(UUID.randomUUID()), Role.TUTOR);
        User student = persisted("mt1-s-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);
        Booking booking = confirmedBooking(approvedCourse(teacher), student, teacher);
        User studentEntity = userRepository.findById(student.getId()).orElseThrow();

        LiveKitTokenService.MeetingToken first = meetingService.token(studentEntity, booking.getId());
        assertThat(meetingSessionRepository.findByBookingId(booking.getId())).isPresent();
        assertThat(first.room_name()).isEqualTo("booking-" + booking.getId());

        Instant firstStartedAt = meetingSessionRepository.findByBookingId(booking.getId()).orElseThrow().getStartedAt();
        Instant firstTokenIssuedAt = meetingSessionRepository.findByBookingId(booking.getId()).orElseThrow().getTokenIssuedAt();

        // повторный вход через >1s: новый access-token, но та же сессия/комната/начало
        Thread.sleep(1200);
        LiveKitTokenService.MeetingToken second = meetingService.token(studentEntity, booking.getId());

        MeetingSession after = meetingSessionRepository.findByBookingId(booking.getId()).orElseThrow();
        assertThat(meetingSessionRepository.count()).isPositive();
        assertThat(second.room_name()).isEqualTo(first.room_name()).isEqualTo("booking-" + booking.getId());
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(after.getStartedAt()).isEqualTo(firstStartedAt);
        assertThat(after.getTokenIssuedAt()).isAfter(firstTokenIssuedAt);
    }

    @Test
    void concurrentTokenRequestsYieldExactlyOneSession() throws Exception {
        User teacher = persisted("mt2-t-%s@test.com".formatted(UUID.randomUUID()), Role.TUTOR);
        User student = persisted("mt2-s-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);
        Booking booking = confirmedBooking(approvedCourse(teacher), student, teacher);
        User studentEntity = userRepository.findById(student.getId()).orElseThrow();
        UUID bookingId = booking.getId();

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        AtomicInteger successes = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    meetingService.token(studentEntity, bookingId);
                    successes.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }

        startLatch.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(failures).isEmpty();
        assertThat(successes.get()).isEqualTo(threads);
        assertThat(meetingSessionRepository.findByBookingId(bookingId)).isPresent();
        assertThat(meetingSessionRepository.findAll().stream()
                .filter(s -> bookingId.equals(s.getBookingId())).count()).isEqualTo(1);
    }

    @Test
    void nonParticipantCannotGetToken() {
        User teacher = persisted("mt3-t-%s@test.com".formatted(UUID.randomUUID()), Role.TUTOR);
        User student = persisted("mt3-s-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);
        User outsider = persisted("mt3-o-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);
        Booking booking = confirmedBooking(approvedCourse(teacher), student, teacher);
        User outsiderEntity = userRepository.findById(outsider.getId()).orElseThrow();

        assertThatThrownBy(() -> meetingService.token(outsiderEntity, booking.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus().value())
                .isEqualTo(403);
    }

    @Test
    void cancelledBookingIsRejectedWithConflict() {
        User teacher = persisted("mt4-t-%s@test.com".formatted(UUID.randomUUID()), Role.TUTOR);
        User student = persisted("mt4-s-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);
        Course course = approvedCourse(teacher);
        Booking booking = confirmedBooking(course, student, teacher);
        booking.setStatus(Booking.Status.CANCELLED);
        bookingRepository.save(booking);
        User studentEntity = userRepository.findById(student.getId()).orElseThrow();

        assertThatThrownBy(() -> meetingService.token(studentEntity, booking.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus().value())
                .isEqualTo(409);
    }
}