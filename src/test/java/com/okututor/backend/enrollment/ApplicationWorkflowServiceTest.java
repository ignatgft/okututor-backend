package com.okututor.backend.enrollment;

import static com.okututor.backend.common.error.ErrorCodes.INVALID_APPLICATION_STATE;
import static com.okututor.backend.common.error.ErrorCodes.NOT_APPLICATION_OWNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.okututor.backend.admin.AuditLog;
import com.okututor.backend.admin.AuditLogRepository;
import com.okututor.backend.admin.AuditLogService;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.course.Course;
import com.okututor.backend.notification.NotificationService;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ApplicationWorkflowServiceTest {

    private EnrollmentRepository repository;
    private AuditLogService auditLogService;
    private AuditLogRepository auditLogRepository;
    private NotificationService notificationService;

    private ApplicationWorkflowService service;

    private User teacher;
    private User student;
    private Enrollment enrollment;

    @BeforeEach
    void setUp() {
        repository = mock(EnrollmentRepository.class);
        auditLogService = mock(AuditLogService.class);
        auditLogRepository = mock(AuditLogRepository.class);
        notificationService = mock(NotificationService.class);

        service = new ApplicationWorkflowService(repository, auditLogService, auditLogRepository,
                notificationService);

        teacher = new User();
        teacher.setId(UUID.randomUUID());
        teacher.setRole(Role.TUTOR);
        teacher.setFirstName("Tut");
        teacher.setLastName("Or");

        student = new User();
        student.setId(UUID.randomUUID());
        student.setRole(Role.STUDENT);
        student.setFirstName("Stu");
        student.setLastName("Dent");

        Course course = new Course();
        course.setTitle("Java Basics");
        course.setTeacher(teacher);

        enrollment = new Enrollment();
        ReflectionTestUtils.setField(enrollment, "id", UUID.randomUUID());
        enrollment.setCourse(course);
        enrollment.setStudent(student);
        enrollment.setTutor(teacher);
        enrollment.setStatus(Enrollment.Status.PENDING);

        when(repository.findById(any())).thenReturn(Optional.of(enrollment));
        when(repository.save(any(Enrollment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void tutorRequestsInfoMovesApplicationToNeedsInfo() {
        service.requestInfo(teacher, enrollment.getId(),
                new ApplicationWorkflowService.RequestInfoRequest("Уточните удобные дни"));

        assertThat(enrollment.getStatus()).isEqualTo(Enrollment.Status.NEEDS_INFO);
        verify(auditLogService).logSync(any());
        verify(notificationService).notify(eq(student.getId()), anyString(), eq(
                "APPLICATION_NEEDS_INFO"), anyString(), any());
    }

    @Test
    void requestInfoWhileNeedsInfoRejectsSecondRequest() {
        enrollment.setStatus(Enrollment.Status.NEEDS_INFO);

        assertThatThrownBy(() -> service.requestInfo(teacher, enrollment.getId(),
                new ApplicationWorkflowService.RequestInfoRequest("ещё раз")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(INVALID_APPLICATION_STATE);
    }

    @Test
    void strangerTutorCannotRequestInfo() {
        User stranger = new User();
        stranger.setId(UUID.randomUUID());
        stranger.setRole(Role.TUTOR);

        assertThatThrownBy(() -> service.requestInfo(stranger, enrollment.getId(),
                new ApplicationWorkflowService.RequestInfoRequest("кто вы?")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("NOT_COURSE_OWNER");
    }

    @Test
    void studentSubmitsInfoBackToPending() {
        enrollment.setStatus(Enrollment.Status.NEEDS_INFO);

        service.submitInfo(student, enrollment.getId(),
                new ApplicationWorkflowService.SubmitInfoRequest("Готов заниматься вечерами"));

        assertThat(enrollment.getStatus()).isEqualTo(Enrollment.Status.PENDING);
        assertThat(enrollment.getMessage()).isEqualTo("Готов заниматься вечерами");
        verify(auditLogService).logSync(any());
        verify(notificationService).notify(eq(teacher.getId()), anyString(), eq(
                "APPLICATION_INFO_SUBMITTED"), anyString(), any());
    }

    @Test
    void timelineReturnsActionsOldToNewForParticipant() {
        AuditLog first = auditLog("SCHEDULE_PROPOSED", "PENDING", "SCHEDULE_PROPOSED");
        AuditLog second = auditLog("SCHEDULE_CONFIRMED", "SCHEDULE_PROPOSED", "SCHEDULED");
        when(auditLogRepository.findByTargetOrderByCreatedAt("APPLICATION", enrollment.getId().toString()))
                .thenReturn(List.of(first, second));

        List<ApplicationWorkflowService.TimelineItemResponse> items = service.timeline(student, enrollment.getId());

        assertThat(items).hasSize(2);
        assertThat(items.get(0).action()).isEqualTo("SCHEDULE_PROPOSED");
        assertThat(items.get(0).old_value()).isEqualTo("PENDING");
        assertThat(items.get(1).new_value()).isEqualTo("SCHEDULED");
        assertThat(items.get(0).actor_id()).isEqualTo(teacher.getId());
    }

    @Test
    void timelineIsDeniedToStrangers() {
        User stranger = new User();
        stranger.setId(UUID.randomUUID());
        stranger.setRole(Role.STUDENT);

        assertThatThrownBy(() -> service.timeline(stranger, enrollment.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(NOT_APPLICATION_OWNER);
    }

    private AuditLog auditLog(String action, String oldValue, String newValue) {
        AuditLog log = new AuditLog();
        log.setActor(teacher);
        log.setAction(action);
        log.setTargetType("APPLICATION");
        log.setTargetId(enrollment.getId().toString());
        log.setOldValue(oldValue);
        log.setNewValue(newValue);
        return log;
    }
}