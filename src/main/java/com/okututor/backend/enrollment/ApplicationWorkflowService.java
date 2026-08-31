package com.okututor.backend.enrollment;

import com.okututor.backend.admin.AuditEntry;
import com.okututor.backend.admin.AuditLog;
import com.okututor.backend.admin.AuditLogRepository;
import com.okututor.backend.admin.AuditLogService;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.ErrorCodes;
import com.okututor.backend.course.Course;
import com.okututor.backend.notification.NotificationService;
import com.okututor.backend.notification.NotificationType;
import com.okututor.backend.user.User;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Операции workflow заявки, которых нет в «старом» EnrollmentService:
 * запрос/предоставление дополнительной информации и таймлайн заявки.
 * Аудит пишется СИНХРОННО (logSync) — так таймлайн заявки формируется
 * в той же транзакции, что и сам переход (нет расхождений во времени).
 */
@Service
public class ApplicationWorkflowService {

    public record RequestInfoRequest(String request) {}
    public record SubmitInfoRequest(String message) {}
    public record TimelineItemResponse(
            String action,
            Instant created_at,
            UUID actor_id,
            String actor_name,
            String actor_role,
            String message,
            String old_value,
            String new_value
    ) {}

    private final EnrollmentRepository repository;
    private final AuditLogService auditLogService;
    private final AuditLogRepository auditLogRepository;
    private final NotificationService notificationService;

    public ApplicationWorkflowService(EnrollmentRepository repository,
                                      AuditLogService auditLogService,
                                      AuditLogRepository auditLogRepository,
                                      NotificationService notificationService) {
        this.repository = repository;
        this.auditLogService = auditLogService;
        this.auditLogRepository = auditLogRepository;
        this.notificationService = notificationService;
    }

    /** единственная точка перехода статуса заявки с аудитом old/new (в текущей транзакции). */
    @Transactional
    public Enrollment transitionTo(Enrollment enrollment, Enrollment.Status target, User actor, String action) {
        String old = enrollment.getStatus().name();
        enrollment.transitionTo(target);
        auditLogService.logSync(AuditEntry.of(actor == null ? null : actor.getId(), action, "APPLICATION",
                enrollment.getId()).withValues(old, target.name()));
        return repository.save(enrollment);
    }

    /** тьютор запрашивает уточнения; заявка PENDING → NEEDS_INFO. */
    @Transactional
    public EnrollmentService.EnrollmentResponse requestInfo(User tutor, UUID applicationId, RequestInfoRequest req) {
        Enrollment enrollment = requireTutorDecisionTarget(applicationId, tutor.getId());
        if (enrollment.getStatus() != Enrollment.Status.PENDING
                && enrollment.getStatus() != Enrollment.Status.NEEDS_INFO) {
            throw ApiException.conflict(ErrorCodes.INVALID_APPLICATION_STATE,
                    "Request-info is only allowed while the application is PENDING");
        }
        transitionTo(enrollment, Enrollment.Status.NEEDS_INFO, tutor, "APPLICATION_REQUEST_INFO");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enrollment_id", enrollment.getId());
        payload.put("course_id", enrollment.getCourse() != null ? enrollment.getCourse().getId() : null);
        String request = req == null || req.request() == null || req.request().isBlank()
                ? "Уточните, пожалуйста, детали заявки"
                : req.request();
        notificationService.notify(
            enrollment.getStudent().getId(),
            "Тьютор курса «" + courseTitle(enrollment) + "» просит уточнить заявку: " + request,
            NotificationType.APPLICATION_NEEDS_INFO,
            "/student/application?id=" + enrollment.getId(),
            payload
        );
        return toResponse(enrollment);
    }

    /** студент предоставляет запрошенные уточнения; NEEDS_INFO → PENDING. */
    @Transactional
    public EnrollmentService.EnrollmentResponse submitInfo(User student, UUID applicationId, SubmitInfoRequest req) {
        Enrollment enrollment = requireOwner(applicationId, student.getId());
        if (enrollment.getStatus() != Enrollment.Status.NEEDS_INFO) {
            throw ApiException.conflict(ErrorCodes.INVALID_APPLICATION_STATE,
                    "There is nothing to answer — the application is not awaiting information");
        }
        if (req != null && req.message() != null && !req.message().isBlank()) {
            enrollment.setMessage(req.message());
        }
        transitionTo(enrollment, Enrollment.Status.PENDING, student, "APPLICATION_INFO_SUBMITTED");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enrollment_id", enrollment.getId());
        payload.put("course_id", enrollment.getCourse() != null ? enrollment.getCourse().getId() : null);
        User tutor = tutorOf(enrollment);
        if (tutor != null) {
            notificationService.notify(tutor.getId(),
                    "Студент «" + enrollment.getStudent().getFullName() + "» уточнил заявку на курс «"
                            + courseTitle(enrollment) + "»",
                    NotificationType.APPLICATION_INFO_SUBMITTED,
                    "/tutor/dashboard?tab=requests&id=" + enrollment.getId(),
                    payload);
        }
        return toResponse(enrollment);
    }

    /**
     * Таймлайн заявки: последовательность действий (application → расписание → занятия)
     * из audit-log. Только участник (студент/тьютор) или администратор.
     */
    @Transactional(readOnly = true)
    public List<TimelineItemResponse> timeline(User viewer, UUID applicationId) {
        Enrollment enrollment = repository.findById(applicationId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.APPLICATION_NOT_FOUND, "Application not found"));
        if (!participant(viewer, enrollment) && !admin(viewer)) {
            throw ApiException.forbidden(ErrorCodes.NOT_APPLICATION_OWNER, "You have no access to this application");
        }
        return auditLogRepository.findByTargetOrderByCreatedAt("APPLICATION", applicationId.toString()).stream()
                .map(a -> new TimelineItemResponse(
                        a.getAction(),
                        a.getCreatedAt(),
                        a.getActor() != null ? a.getActor().getId() : null,
                        a.getActor() != null ? a.getActor().getFullName() : "Система",
                        a.getActor() != null ? (a.getActor().getRole() != null ? a.getActor().getRole().name() : null) : null,
                        labelFor(a),
                        a.getOldValue(),
                        a.getNewValue()))
                .toList();
    }

    private static String labelFor(AuditLog a) {
        if (a.getDetails() != null && !a.getDetails().isBlank()) {
            return a.getDetails();
        }
        return switch (a.getAction()) {
            case "APPLICATION_REQUEST_INFO" -> "Тьютор запросил уточнения по заявке";
            case "APPLICATION_INFO_SUBMITTED" -> "Студент уточнил детали заявки";
            case "APPLICATION_CANCELLED" -> "Заявка отменена";
            case "APPLICATION_ACCEPTED" -> "Заявка принята тьютором";
            case "APPLICATION_REJECTED" -> "Заявка отклонена";
            case "SCHEDULE_PROPOSED" -> "Предложено расписание занятий";
            case "SCHEDULE_CONFIRMED" -> "Расписание подтверждено";
            case "SCHEDULE_REJECTED" -> "Студент отклонил предложенное расписание";
            case "SCHEDULE_COUNTER" -> "Студент предложил встречный вариант расписания";
            case "LESSONS_GENERATED" -> "Созданы конкретные занятия по расписанию";
            default -> a.getAction();
        };
    }

    private Enrollment requireTutorDecisionTarget(UUID enrollmentId, UUID teacherId) {
        Enrollment enrollment = requireById(enrollmentId);
        User courseTeacher = tutorOf(enrollment);
        if (courseTeacher == null || !teacherId.equals(courseTeacher.getId())) {
            throw ApiException.forbidden(ErrorCodes.NOT_COURSE_OWNER, "Only the course tutor can decide on this request");
        }
        return enrollment;
    }

    private Enrollment requireOwner(UUID enrollmentId, UUID studentId) {
        Enrollment enrollment = requireById(enrollmentId);
        if (enrollment.getStudent() == null || !studentId.equals(enrollment.getStudent().getId())) {
            throw ApiException.forbidden(ErrorCodes.NOT_APPLICATION_OWNER, "Not your application");
        }
        return enrollment;
    }

    private Enrollment requireById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.APPLICATION_NOT_FOUND, "Application not found"));
    }

    private static boolean participant(User viewer, Enrollment enrollment) {
        if (viewer == null) {
            return false;
        }
        if (enrollment.getStudent() != null && viewer.getId().equals(enrollment.getStudent().getId())) {
            return true;
        }
        User tutor = tutorOf(enrollment);
        return tutor != null && viewer.getId().equals(tutor.getId());
    }

    private static boolean admin(User viewer) {
        return viewer != null && (viewer.getRole() == com.okututor.backend.user.Role.ADMIN
                || viewer.getRole() == com.okututor.backend.user.Role.SUPER_ADMIN);
    }

    private static User tutorOf(Enrollment enrollment) {
        Course course = enrollment.getCourse();
        if (course != null && course.getTeacher() != null) {
            return course.getTeacher();
        }
        return null;
    }

    private static String courseTitle(Enrollment e) {
        return e.getCourse() != null ? e.getCourse().getTitle() : "Курс";
    }

    private static EnrollmentService.EnrollmentResponse toResponse(Enrollment e) {
        Course course = e.getCourse();
        User student = e.getStudent();
        User tutor = e.getTutor() != null ? e.getTutor()
                : (course != null ? course.getTeacher() : null);
        return new EnrollmentService.EnrollmentResponse(
                e.getId(),
                e.getStatus().name(),
                e.getMessage(),
                e.getPreferredSchedule(),
                course != null ? course.getId() : null,
                course != null ? course.getTitle() : null,
                student != null ? student.getId() : null,
                student != null ? student.getFullName() : null,
                tutor != null ? tutor.getId() : null,
                tutor != null ? tutor.getId() : null,
                e.getPreferredFormat(),
                e.getPreferredDays(),
                e.getPreferredStartTime() != null ? e.getPreferredStartTime().toString() : null,
                e.getPreferredEndTime() != null ? e.getPreferredEndTime().toString() : null,
                e.getFrequency(),
                e.getDurationMinutes(),
                e.getExpiresAt(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}