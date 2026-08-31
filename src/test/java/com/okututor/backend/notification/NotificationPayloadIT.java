package com.okututor.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * payload пишется/читается как jsonb: без Hibernate jsonb/varchar mismatch.
 * Требует Docker (mvn verify).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class NotificationPayloadIT {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine").withReuse(false);

    @Autowired UserRepository userRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired NotificationService notificationService;
    @Autowired JdbcTemplate jdbcTemplate;

    private User persisted(String email, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setVerified(true);
        u.setRole(role);
        return userRepository.save(u);
    }

    @Test
    void payloadIsStoredAsJsonbAndReadBack() {
        User student = persisted("np-s-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);
        User teacher = persisted("np-t-%s@test.com".formatted(UUID.randomUUID()), Role.TUTOR);

        UUID enrollmentId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enrollment_id", enrollmentId.toString());
        payload.put("course_id", courseId.toString());
        payload.put("booking_id", UUID.randomUUID().toString());
        payload.put("duration_minutes", 60);

        notificationService.notify(teacher.getId(),
                "Тест jsonb-уведомления", NotificationType.COURSE_APPLICATION, "/tutor/dashboard", payload);

        await().atMost(15, TimeUnit.SECONDS).until(() ->
                notificationRepository.findByUserIdAndReadFalse(teacher.getId()).stream()
                        .anyMatch(n -> NotificationType.COURSE_APPLICATION.equals(n.getType())));

        Notification saved = notificationRepository.findByUserIdAndReadFalse(teacher.getId()).stream()
                .filter(n -> NotificationType.COURSE_APPLICATION.equals(n.getType()))
                .findFirst().orElseThrow();

        // читается обратно как Map, а не как закодированная юникод-строка или double-encoded JSON
        assertThat(saved.getPayload())
                .containsEntry("enrollment_id", enrollmentId.toString())
                .containsEntry("course_id", courseId.toString())
                .containsEntry("duration_minutes", 60);

        // в самом PostgreSQL колонка — jsonb, значение — JSON-объект, а не строка-в-строке
        String type = jdbcTemplate.queryForObject(
                "select data_type from information_schema.columns "
                        + "where table_name = 'notifications' and column_name = 'payload'", String.class);
        assertThat(type).isEqualTo("jsonb");
        String raw = jdbcTemplate.queryForObject(
                "select payload::text from notifications where id = ?", String.class, saved.getId());
        assertThat(raw).startsWith("{").contains("\"enrollment_id\"");
    }
}