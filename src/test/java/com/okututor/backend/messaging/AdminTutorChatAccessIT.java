package com.okututor.backend.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.tutors.TutorApplication;
import com.okututor.backend.tutors.TutorApplicationRepository;
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

/**
 * Права чата админ↔заявитель (B.2, C): админ открывает диалог с любым,
 * посторонний студент — 403.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class AdminTutorChatAccessIT {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine").withReuse(false);

    @Autowired UserRepository userRepository;
    @Autowired TutorApplicationRepository tutorApplicationRepository;
    @Autowired MessagingService messagingService;

    private User persisted(String email, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setRole(role);
        u.setVerified(true);
        return userRepository.save(u);
    }

    @Test
    void adminCanOpenWithAnyone() {
        User admin = persisted("adm-%s@test.com".formatted(UUID.randomUUID()), Role.ADMIN);
        User stranger = persisted("str-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);
        // не должно быть исключения
        messagingService.ensureCanOpen(admin, stranger.getId());
    }

    @Test
    void applicantCanOpenWithAdmin() {
        User applicant = persisted("app-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);
        User admin = persisted("adm2-%s@test.com".formatted(UUID.randomUUID()), Role.ADMIN);
        TutorApplication app = new TutorApplication();
        app.setUser(applicant);
        app.setFullName("Applicant");
        tutorApplicationRepository.save(app);

        messagingService.ensureCanOpen(applicant, admin.getId());
        // и открыть реально
        MessagingService.ConversationResponse conv = messagingService.openWith(applicant, admin.getId());
        assertThat(conv.id()).isNotNull();
    }

    @Test
    void unrelatedStudentIsForbidden() {
        User applicant = persisted("app2-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);
        User outsider = persisted("out-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);
        // нет ни связи, ни заявки тьютора → 403
        assertThatThrownBy(() -> messagingService.ensureCanOpen(outsider, applicant.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("FORBIDDEN");
    }
}
