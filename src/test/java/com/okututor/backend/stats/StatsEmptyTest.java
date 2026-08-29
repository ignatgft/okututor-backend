package com.okututor.backend.stats;

import static org.assertj.core.api.Assertions.assertThat;

import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** пустая статистика — нули, не 404/500 (D.4). */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class StatsEmptyTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine").withReuse(false);

    @Autowired UserRepository userRepository;
    @Autowired StatsService statsService;

    @Test
    void newStudentHasZeros() {
        User student = new User();
        student.setEmail("new-st-%s@test.com".formatted(UUID.randomUUID()));
        student.setRole(Role.STUDENT);
        student.setVerified(true);
        student = userRepository.save(student);

        StatsService.StudentStats stats = statsService.studentStats(student);
        assertThat(stats.completed_lessons()).isZero();
        assertThat(stats.upcoming_lessons()).isZero();
        assertThat(stats.total_hours()).isZero();
        assertThat(stats.courses_count()).isZero();
        assertThat(stats.average_rating_given()).isZero();
        assertThat(stats.by_month()).isEmpty();
    }

    @Test
    void newTutorHasZeros() {
        User tutor = new User();
        tutor.setEmail("new-tu-%s@test.com".formatted(UUID.randomUUID()));
        tutor.setRole(Role.TUTOR);
        tutor.setVerified(true);
        tutor = userRepository.save(tutor);

        StatsService.TutorStats stats = statsService.tutorStats(tutor);
        assertThat(stats.students_count()).isZero();
        assertThat(stats.completed_lessons()).isZero();
        assertThat(stats.upcoming()).isZero();
        assertThat(stats.total_hours()).isZero();
        assertThat(stats.average_rating()).isZero();
        assertThat(stats.pending_requests()).isZero();
    }
}
