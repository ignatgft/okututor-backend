package com.okututor.backend.enrollment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.okututor.backend.booking.Booking;
import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.course.Course;
import com.okututor.backend.course.CourseRepository;
import com.okututor.backend.notification.Notification;
import com.okututor.backend.notification.NotificationRepository;
import com.okututor.backend.notification.NotificationType;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Полный набор уведомлений заявок на курс: enroll/accept/acceptAndSchedule/reject/cancel
 * → правильный type и получатель. Требует Docker (mvn verify).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class EnrollmentNotificationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine").withReuse(false);

    @Autowired UserRepository userRepository;
    @Autowired CourseRepository courseRepository;
    @Autowired EnrollmentService enrollmentService;
    @Autowired NotificationRepository notificationRepository;
    @Autowired BookingRepository bookingRepository;

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
        c.setTitle("Physics");
        c.setSubject("Physics");
        c.setPricePerHour(BigDecimal.valueOf(1200));
        c.setStatus(Course.Status.APPROVED);
        return courseRepository.save(c);
    }

    private void awaitType(UUID userId, String type, long count) {
        await().atMost(15, TimeUnit.SECONDS).until(() ->
                notificationRepository.findByUserIdAndReadFalse(userId).stream()
                        .filter(n -> type.equals(n.getType()))
                        .count() == count);
    }

    @Test
    void fullLifecycleFiresCorrectNotifications() {
        User teacher = persisted("t-%s@test.com".formatted(UUID.randomUUID()), Role.TUTOR);
        User student = persisted("s-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);
        Course course = approvedCourse(teacher);

        // enroll → тьютору COURSE_APPLICATION
        EnrollmentService.EnrollmentResponse er = enrollmentService.enroll(student, course.getId(), "hi", null);
        awaitType(teacher.getId(), NotificationType.COURSE_APPLICATION, 1);

        // accept → студенту APPLICATION_ACCEPTED
        enrollmentService.accept(teacher, er.id());
        awaitType(student.getId(), NotificationType.APPLICATION_ACCEPTED, 1);

        // reject → студенту APPLICATION_REJECTED
        User student2 = persisted("s2-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);
        EnrollmentService.EnrollmentResponse er2 = enrollmentService.enroll(student2, course.getId(), "me too", null);
        awaitType(teacher.getId(), NotificationType.COURSE_APPLICATION, 2);
        enrollmentService.reject(teacher, er2.id());
        awaitType(student2.getId(), NotificationType.APPLICATION_REJECTED, 1);

        // acceptAndSchedule → студенту APPLICATION_ACCEPTED + CONFIRMED Booking, связанный с заявкой
        User student3 = persisted("s3-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);
        EnrollmentService.EnrollmentResponse er3 = enrollmentService.enroll(student3, course.getId(), "schedule me", null);
        awaitType(teacher.getId(), NotificationType.COURSE_APPLICATION, 3);
        String futureDate = Instant.now().plusSeconds(3600L * 24 * 14).toString().substring(0, 10);
        enrollmentService.acceptAndSchedule(teacher, er3.id(),
                new EnrollmentService.AcceptAndScheduleRequest(futureDate, "10:00", 60, "Asia/Bishkek", null));
        awaitType(student3.getId(), NotificationType.APPLICATION_ACCEPTED, 1);

        List<Booking> bookings = bookingRepository.findByStudentIdOrderByStartAtDesc(student3.getId(),
                PageRequest.of(0, 10)).getContent();
        assertThat(bookings).hasSize(1);
        assertThat(bookings.get(0).getStatus()).isEqualTo(Booking.Status.CONFIRMED);
        assertThat(bookings.get(0).getEnrollment()).isNotNull();
        // уведомление accept-and-schedule несёт structured payload с booking_id
        Notification accepted = notificationRepository.findByUserIdAndReadFalse(student3.getId()).stream()
                .filter(n -> NotificationType.APPLICATION_ACCEPTED.equals(n.getType()))
                .findFirst().orElseThrow();
        assertThat(accepted.getPayload()).containsKey("booking_id");

        // cancel → тьютору APPLICATION_CANCELLED
        User student4 = persisted("s4-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);
        EnrollmentService.EnrollmentResponse er4 = enrollmentService.enroll(student4, course.getId(), "then cancel", null);
        awaitType(teacher.getId(), NotificationType.COURSE_APPLICATION, 4);
        enrollmentService.cancel(student4, er4.id());
        awaitType(teacher.getId(), NotificationType.APPLICATION_CANCELLED, 1);
    }
}
