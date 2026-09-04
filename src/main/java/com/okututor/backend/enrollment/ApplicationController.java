package com.okututor.backend.enrollment;

import com.okututor.backend.booking.ScheduleParser;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.FieldValidationException;
import com.okututor.backend.schedule.ScheduleService;
import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserService;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Полный workflow заявки (spec §32 §34): создание с предпочтениями, запрос/ответ
 * на уточнения, таймлайн, согласование расписания и подбор свободных окон.
 */
@RestController
public class ApplicationController {

    public record ApplicationCreateRequest(
            UUID course_id,
            String message,
            String preferred_schedule,
            String preferred_format,
            java.util.List<String> preferred_days,
            String preferred_start_time,
            String preferred_end_time,
            String frequency,
            Integer duration_minutes
    ) {}

    public record RequestInfoRequest(String request) {}
    public record SubmitInfoRequest(String message) {}
    public record RejectRequest(String reason) {}

    private final EnrollmentService enrollmentService;
    private final ApplicationWorkflowService workflowService;
    private final ScheduleService scheduleService;
    private final UserService userService;

    public ApplicationController(EnrollmentService enrollmentService,
                                 ApplicationWorkflowService workflowService,
                                 ScheduleService scheduleService,
                                 UserService userService) {
        this.enrollmentService = enrollmentService;
        this.workflowService = workflowService;
        this.scheduleService = scheduleService;
        this.userService = userService;
    }

    /** создание заявки (в т.ч. прямой запрос) с указанием предпочтений по занятиям. */
    @PostMapping("/api/v1/applications")
    @PreAuthorize("hasRole('STUDENT')")
    public EnrollmentService.EnrollmentResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                                       @RequestBody ApplicationCreateRequest request) {
        if (request == null || request.course_id() == null) {
            throw new FieldValidationException(Map.of("course_id", "course_id is required"));
        }
        return enrollmentService.enroll(currentUser(principal), request.course_id(),
                request.message(), request.preferred_schedule(),
                request.preferred_format(), request.preferred_days(),
                parseTimeOf(request.preferred_start_time()), parseTimeOf(request.preferred_end_time()),
                request.frequency(), request.duration_minutes());
    }

    @GetMapping("/api/v1/applications/my")
    @PreAuthorize("hasRole('STUDENT')")
    public Page<EnrollmentService.EnrollmentResponse> myApplications(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return enrollmentService.myEnrollments(principal.id(), page, size);
    }

    /** alias для привычного фронту пути «запросы тьютору». */
    @GetMapping("/api/v1/tutors/me/applications")
    @PreAuthorize("hasRole('TUTOR')")
    public Page<EnrollmentService.EnrollmentResponse> tutorApplications(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return enrollmentService.tutorRequests(principal.id(), page, size);
    }

    @GetMapping("/api/v1/applications/{id}")
    public EnrollmentService.EnrollmentResponse byId(@AuthenticationPrincipal UserPrincipal principal,
                                                     @PathVariable UUID id) {
        return enrollmentService.getById(currentUser(principal), id);
    }

    @PostMapping("/api/v1/applications/{id}/cancel")
    @PreAuthorize("hasRole('STUDENT')")
    public void cancel(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        enrollmentService.cancel(currentUser(principal), id);
    }

    @PostMapping("/api/v1/applications/{id}/accept")
    @PreAuthorize("hasRole('TUTOR')")
    public EnrollmentService.EnrollmentResponse accept(@AuthenticationPrincipal UserPrincipal principal,
                                                       @PathVariable UUID id) {
        return enrollmentService.accept(currentUser(principal), id);
    }

    @PostMapping("/api/v1/applications/{id}/reject")
    @PreAuthorize("hasRole('TUTOR')")
    public EnrollmentService.EnrollmentResponse reject(@AuthenticationPrincipal UserPrincipal principal,
                                                       @PathVariable UUID id,
                                                       @RequestBody(required = false) RejectRequest request) {
        return enrollmentService.reject(currentUser(principal), id,
                request == null ? null : request.reason());
    }

    @PostMapping("/api/v1/applications/{id}/request-info")
    @PreAuthorize("hasRole('TUTOR')")
    public EnrollmentService.EnrollmentResponse requestInfo(@AuthenticationPrincipal UserPrincipal principal,
                                                            @PathVariable UUID id,
                                                            @RequestBody(required = false) Map<String, Object> body) {
        String request = body == null ? null : firstNonBlank(
                objToStr(body.get("request")), objToStr(body.get("question")), objToStr(body.get("message")));
        return workflowService.requestInfo(currentUser(principal), id,
                new ApplicationWorkflowService.RequestInfoRequest(request));
    }

    @PostMapping("/api/v1/applications/{id}/submit-info")
    @PreAuthorize("hasRole('STUDENT')")
    @io.swagger.v3.oas.annotations.Operation(summary = "Ответ ученика на запрос уточнений",
            description = "Единственный канонический контракт: { \"message\": string }. "
                    + "Поля request/question не поддерживаются — фронт должен слать только message.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Заявка переведена из NEEDS_INFO в PENDING")
    public EnrollmentService.EnrollmentResponse submitInfo(@AuthenticationPrincipal UserPrincipal principal,
                                                           @PathVariable UUID id,
                                                           @RequestBody(required = false) ApplicationWorkflowService.SubmitInfoRequest request) {
        return workflowService.submitInfo(currentUser(principal), id, request);
    }

    @PostMapping("/api/v1/applications/{id}/provide-info")
    @PreAuthorize("hasRole('STUDENT')")
    @io.swagger.v3.oas.annotations.Operation(summary = "[deprecated alias] /applications/{id}/submit-info",
            description = "Алиас того же действия: канонический контракт — POST /api/v1/applications/{id}/submit-info { \"message\": string }.")
    public EnrollmentService.EnrollmentResponse provideInfo(@AuthenticationPrincipal UserPrincipal principal,
                                                            @PathVariable UUID id,
                                                            @RequestBody(required = false) Map<String, Object> body) {
        String message = body == null ? null : firstNonBlank(
                objToStr(body.get("message")), objToStr(body.get("request")), objToStr(body.get("question")));
        return workflowService.submitInfo(currentUser(principal), id,
                new ApplicationWorkflowService.SubmitInfoRequest(message));
    }

    /** таймлайн заявки: заявка → расписание → занятия (audit-log, участник или админ). */
    @GetMapping("/api/v1/applications/{id}/timeline")
    public java.util.List<ApplicationWorkflowService.TimelineItemResponse> timeline(
            @AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        return workflowService.timeline(currentUser(principal), id);
    }

    /** свободные окна: пересечение предпочтений студента и доступности тьютора. */
    @GetMapping("/api/v1/applications/{id}/available-slots")
    public java.util.List<ScheduleService.AvailableSlotResponse> availableSlots(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @RequestParam(required = false) String from_date,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to_date,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String timezone) {
        String resolvedFrom = firstNonBlank(from, from_date);
        String resolvedTo = firstNonBlank(to, to_date);
        if (resolvedFrom == null || resolvedTo == null) {
            throw ApiException.validation("from and to (yyyy-MM-dd) are required");
        }
        return scheduleService.availableSlots(currentUser(principal), id, resolvedFrom, resolvedTo, timezone);
    }

    /** история предложений расписания по заявке. */
    @GetMapping("/api/v1/applications/{id}/proposals")
    public java.util.List<ScheduleService.ScheduleProposalResponse> proposals(
            @AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        return scheduleService.listProposals(currentUser(principal), id);
    }

    /** тьютор предлагает расписание по принятой заявке. */
    @PostMapping("/api/v1/applications/{id}/schedule/propose")
    @PreAuthorize("hasRole('TUTOR')")
    public ScheduleService.ScheduleProposalResponse propose(@AuthenticationPrincipal UserPrincipal principal,
                                                            @PathVariable UUID id,
                                                            @RequestBody ScheduleService.ProposeRequest request) {
        return scheduleService.propose(currentUser(principal), id, request);
    }

    private static LocalTime parseTimeOf(String value) {
        return value == null || value.isBlank() ? null : ScheduleParser.parseTime(value);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String objToStr(Object o) {
        return o == null ? null : o.toString();
    }

    private User currentUser(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        return userService.requireById(principal.id());
    }
}