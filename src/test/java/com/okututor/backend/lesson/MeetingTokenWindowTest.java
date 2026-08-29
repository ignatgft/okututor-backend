package com.okututor.backend.lesson;

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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * JOIN окно: вне временного интервала [startAt−N, endAt+M] токен не выдаётся,
 * вернётся 403 MEETING_NOT_AVAILABLE (E).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class MeetingTokenWindowTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine").withReuse(false);

    @Autowired UserRepository userRepository;
    @Autowired CourseRepository courseRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired MeetingService meetingService;

    @Test
    void outsideWindowIsForbiddenWithMeetingNotAvailable() {
        User teacher = new User();
        teacher.setEmail("mw-t-%s@test.com".formatted(UUID.randomUUID()));
        teacher.setRole(Role.TUTOR);
        teacher.setVerified(true);
        teacher = userRepository.save(teacher);

        User student = new User();
        student.setEmail("mw-s-%s@test.com".formatted(UUID.randomUUID()));
        student.setRole(Role.STUDENT);
        student.setVerified(true);
        student = userRepository.save(student);

        Course course = new Course();
        course.setTeacher(teacher);
        course.setTitle("Algebra");
        course.setSubject("Algebra");
        course.setPricePerHour(BigDecimal.valueOf(800));
        course.setStatus(Course.Status.APPROVED);
        course = courseRepository.save(course);

        // бронь в далёком прошлом, статус CONFIRMED → now() точно вне окна
        Booking booking = new Booking();
        booking.setCourse(course);
        booking.setStudent(student);
        booking.setTeacher(teacher);
        booking.setStartAt(Instant.now().minusSeconds(3600L * 24 * 30));
        booking.setEndAt(booking.getStartAt().plusSeconds(3600));
        booking.setDurationMinutes(60);
        booking.setStatus(Booking.Status.CONFIRMED);
        Booking saved = bookingRepository.save(booking);
        UUID bookingId = saved.getId();
        UUID studentId = student.getId();

        assertThatThrownBy(() -> meetingService.token(userRepository.findById(studentId).orElseThrow(), bookingId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("MEETING_NOT_AVAILABLE");
    }
}
