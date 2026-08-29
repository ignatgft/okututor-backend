package com.okututor.backend.enrollment;

import static org.assertj.core.api.Assertions.assertThat;

import com.okututor.backend.course.Course;
import com.okututor.backend.course.CourseRepository;
import com.okututor.backend.messaging.Conversation;
import com.okututor.backend.messaging.ConversationRepository;
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

/** после accept() создаётся DIRECT-переписка студент↔тьютор (A.5, C). */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class ConversationOnAcceptTest {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine").withReuse(false);

    @Autowired UserRepository userRepository;
    @Autowired CourseRepository courseRepository;
    @Autowired EnrollmentService enrollmentService;
    @Autowired ConversationRepository conversationRepository;

    @Test
    void acceptCreatesDirectConversation() {
        User teacher = new User();
        teacher.setEmail("ct-%s@test.com".formatted(UUID.randomUUID()));
        teacher.setRole(Role.TUTOR);
        teacher.setVerified(true);
        teacher = userRepository.save(teacher);

        User student = new User();
        student.setEmail("cs-%s@test.com".formatted(UUID.randomUUID()));
        student.setRole(Role.STUDENT);
        student.setVerified(true);
        student = userRepository.save(student);

        Course course = new Course();
        course.setTeacher(teacher);
        course.setTitle("Chemistry");
        course.setSubject("Chemistry");
        course.setPricePerHour(BigDecimal.valueOf(900));
        course.setStatus(Course.Status.APPROVED);
        Course saved = courseRepository.save(course);

        EnrollmentService.EnrollmentResponse er = enrollmentService.enroll(student, saved.getId(), "hello", null);
        assertThat(conversationRepository.findDirectForUser(student.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10))).isEmpty();

        enrollmentService.accept(teacher, er.id());

        var convs = conversationRepository.findDirectForUser(student.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(convs).hasSize(1);
        assertThat(convs.get(0).getType()).isEqualTo(Conversation.Type.DIRECT);
        assertThat(convs.get(0).involves(teacher.getId())).isTrue();
    }
}
