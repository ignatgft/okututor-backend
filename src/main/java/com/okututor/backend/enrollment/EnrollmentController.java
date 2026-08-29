package com.okututor.backend.enrollment;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserService;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EnrollmentController {

    public record EnrollRequest(String message, String preferred_schedule) {}

    private final EnrollmentService enrollmentService;
    private final UserService userService;

    public EnrollmentController(EnrollmentService enrollmentService, UserService userService) {
        this.enrollmentService = enrollmentService;
        this.userService = userService;
    }

    @PostMapping("/api/v1/courses/{courseId}/enroll")
    @PreAuthorize("hasRole('STUDENT')")
    public EnrollmentService.EnrollmentResponse enroll(@AuthenticationPrincipal UserPrincipal principal,
                                                       @PathVariable UUID courseId,
                                                       @RequestBody(required = false) EnrollRequest request) {
        return enrollmentService.enroll(currentUser(principal), courseId,
                request == null ? null : request.message(),
                request == null ? null : request.preferred_schedule());
    }

    @GetMapping("/api/v1/students/me/enrollments")
    @PreAuthorize("hasRole('STUDENT')")
    public Page<EnrollmentService.EnrollmentResponse> myEnrollments(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return enrollmentService.myEnrollments(principal.id(), page, size);
    }

    @GetMapping("/api/v1/tutors/me/requests")
    @PreAuthorize("hasRole('TUTOR')")
    public Page<EnrollmentService.EnrollmentResponse> tutorRequests(@AuthenticationPrincipal UserPrincipal principal,
                                                                    @RequestParam(defaultValue = "0") int page,
                                                                    @RequestParam(defaultValue = "20") int size) {
        return enrollmentService.tutorRequests(principal.id(), page, size);
    }

    /** запись текущего пользователя на курс; NOT_REQUESTED, если её нет. */
    @GetMapping("/api/v1/courses/{courseId}/enrollment")
    public EnrollmentService.EnrollmentResponse forCourse(@AuthenticationPrincipal UserPrincipal principal,
                                                          @PathVariable UUID courseId) {
        if (principal == null) {
            return EnrollmentService.EnrollmentResponse.notRequested();
        }
        return enrollmentService.forCourse(currentUser(principal), courseId);
    }

    /** просмотр заявки по id: участник (студент/тьютор курса) или ADMIN. Диплинки из уведомлений. */
    @GetMapping("/api/v1/enrollments/{id}")
    public EnrollmentService.EnrollmentResponse byId(@AuthenticationPrincipal UserPrincipal principal,
                                                     @PathVariable UUID id) {
        return enrollmentService.getById(currentUser(principal), id);
    }

    @org.springframework.transaction.annotation.Transactional
    @DeleteMapping("/api/v1/enrollments/{id}")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        enrollmentService.cancel(currentUser(principal), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/enrollments/{id}/accept")
    @PreAuthorize("hasRole('TUTOR')")
    public EnrollmentService.EnrollmentResponse accept(@AuthenticationPrincipal UserPrincipal principal,
                                                       @PathVariable UUID id) {
        return enrollmentService.accept(currentUser(principal), id);
    }

    @PostMapping("/api/v1/enrollments/{id}/reject")
    @PreAuthorize("hasRole('TUTOR')")
    public EnrollmentService.EnrollmentResponse reject(@AuthenticationPrincipal UserPrincipal principal,
                                                       @PathVariable UUID id) {
        return enrollmentService.reject(currentUser(principal), id);
    }

    @PostMapping("/api/v1/enrollments/{id}/accept-and-schedule")
    @PreAuthorize("hasRole('TUTOR')")
    public EnrollmentService.AcceptAndScheduleResponse acceptAndSchedule(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @RequestBody EnrollmentService.AcceptAndScheduleRequest request) {
        return enrollmentService.acceptAndSchedule(currentUser(principal), id, request);
    }

    static void requireAuth(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("Authentication required");
        }
    }

    private User currentUser(UserPrincipal principal) {
        requireAuth(principal);
        return userService.requireById(principal.id());
    }
}
