package com.okututor.backend.enrollment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.course.Course;
import com.okututor.backend.course.CourseRepository;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Дубли исторических enrollment (REJECTED/CANCELLED) не должны валить
 * forCourse (NonUniqueResultException); активная заявка детерминированно
 * выбирается; повторная запись после отмены работает; дубль активной блокируется.
 * Требует Docker (mvn verify).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class EnrollmentHistoryIT {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine").withReuse(false);

    @Autowired UserRepository userRepository;
    @Autowired CourseRepository courseRepository;
    @Autowired EnrollmentService enrollmentService;
    @Autowired EnrollmentRepository enrollmentRepository;

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
        c.setTitle("Enrollment History Math");
        c.setSubject("Math");
        c.setPricePerHour(BigDecimal.valueOf(900));
        c.setStatus(Course.Status.APPROVED);
        return courseRepository.save(c);
    }

    @Test
    void historicalDuplicatesDoNotBreakForCourse() {
        User teacher = persisted("eh1-t-%s@test.com".formatted(UUID.randomUUID()), Role.TUTOR);
        User student = persisted("eh1-s-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);
        Course course = approvedCourse(teacher);
        UUID courseId = course.getId();
        User studentEntity = userRepository.findById(student.getId()).orElseThrow();
        User teacherEntity = userRepository.findById(teacher.getId()).orElseThrow();

        // 3 исторические записи: CANCELLED, CANCELLED, REJECTED — без активной
        EnrollmentService.EnrollmentResponse e1 = enrollmentService.enroll(studentEntity, courseId, "1", null);
        enrollmentService.cancel(studentEntity, e1.id());
        EnrollmentService.EnrollmentResponse e2 = enrollmentService.enroll(studentEntity, courseId, "2", null);
        enrollmentService.cancel(studentEntity, e2.id());
        EnrollmentService.EnrollmentResponse e3 = enrollmentService.enroll(studentEntity, courseId, "3", null);
        enrollmentService.reject(teacherEntity, e3.id());

        assertThat(enrollmentRepository.count()).isGreaterThanOrEqualTo(3);

        // НЕ должно быть NonUniqueResultException: актуальная (REJECTED) выбирается детерминированно
        EnrollmentService.EnrollmentResponse current = enrollmentService.forCourse(studentEntity, courseId);
        assertThat(current.status()).isEqualTo("REJECTED");
        assertThat(current.id()).isEqualTo(e3.id());
    }

    @Test
    void activeEnrollmentIsPreferredOverLatestHistory() {
        User teacher = persisted("eh2-t-%s@test.com".formatted(UUID.randomUUID()), Role.TUTOR);
        User student = persisted("eh2-s-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);
        Course course = approvedCourse(teacher);
        UUID courseId = course.getId();
        User studentEntity = userRepository.findById(student.getId()).orElseThrow();
        User teacherEntity = userRepository.findById(teacher.getId()).orElseThrow();

        EnrollmentService.EnrollmentResponse first = enrollmentService.enroll(studentEntity, courseId, "old", null);
        enrollmentService.reject(teacherEntity, first.id());

        EnrollmentService.EnrollmentResponse active = enrollmentService.enroll(studentEntity, courseId, "new", null);
        enrollmentService.accept(teacherEntity, active.id());

        EnrollmentService.EnrollmentResponse current = enrollmentService.forCourse(studentEntity, courseId);
        assertThat(current.status()).isEqualTo("ACCEPTED");
        assertThat(current.id()).isEqualTo(active.id());
    }

    @Test
    void reEnrollmentAfterCancellationWorks() {
        User teacher = persisted("eh3-t-%s@test.com".formatted(UUID.randomUUID()), Role.TUTOR);
        User student = persisted("eh3-s-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);
        Course course = approvedCourse(teacher);
        UUID courseId = course.getId();
        User studentEntity = userRepository.findById(student.getId()).orElseThrow();

        EnrollmentService.EnrollmentResponse first = enrollmentService.enroll(studentEntity, courseId, "first", null);
        enrollmentService.cancel(studentEntity, first.id());

        EnrollmentService.EnrollmentResponse second = enrollmentService.enroll(studentEntity, courseId, "second", null);
        assertThat(second.status()).isEqualTo("PENDING");
        EnrollmentService.EnrollmentResponse current = enrollmentService.forCourse(studentEntity, courseId);
        assertThat(current.id()).isEqualTo(second.id()).isNotEqualTo(first.id());
    }

    @Test
    void duplicateActiveEnrollmentIsRejected() {
        User teacher = persisted("eh4-t-%s@test.com".formatted(UUID.randomUUID()), Role.TUTOR);
        User student = persisted("eh4-s-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);
        Course course = approvedCourse(teacher);
        UUID courseId = course.getId();
        User studentEntity = userRepository.findById(student.getId()).orElseThrow();
        User teacherEntity = userRepository.findById(teacher.getId()).orElseThrow();

        EnrollmentService.EnrollmentResponse accepted =
                enrollmentService.enroll(studentEntity, courseId, "active", null);
        enrollmentService.accept(teacherEntity, accepted.id());

        assertThatThrownBy(() -> enrollmentService.enroll(studentEntity, courseId, "dup", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already have a request");
    }
}