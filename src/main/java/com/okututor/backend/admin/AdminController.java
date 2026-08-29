package com.okututor.backend.admin;

import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.course.Course;
import com.okututor.backend.course.CourseService;
import com.okututor.backend.review.Review;
import com.okututor.backend.review.ReviewService;
import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.tutors.TutorApplicationService;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * поверхность админ-модерации. Каждое изменяющее действие пишется в аудит; RBAC enforced
 * и через @PreAuthorize, и явными guard-ами (defense in depth).
 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class AdminController {

    public record RoleRequest(String role) {}
    public record ReasonRequest(String reason) {}

    public record AdminUserResponse(
            UUID id,
            String email,
            String full_name,
            String role,
            boolean verified,
            boolean blocked,
            java.time.Instant created_at
    ) {}

    private final CourseService courseService;
    private final ReviewService reviewService;
    private final TutorApplicationService tutorApplicationService;
    private final BookingRepository bookingRepository;
    private final com.okututor.backend.user.UserRepository userRepository;
    private final com.okututor.backend.enrollment.EnrollmentRepository enrollmentRepository;
    private final com.okututor.backend.tutors.TutorApplicationRepository tutorApplicationRepository;
    private final com.okututor.backend.review.ReviewRepository reviewRepository;
    private final AuditLogService auditLog;

    public AdminController(CourseService courseService,
                           ReviewService reviewService,
                           TutorApplicationService tutorApplicationService,
                           BookingRepository bookingRepository,
                           com.okututor.backend.user.UserRepository userRepository,
                           com.okututor.backend.enrollment.EnrollmentRepository enrollmentRepository,
                           com.okututor.backend.tutors.TutorApplicationRepository tutorApplicationRepository,
                           com.okututor.backend.review.ReviewRepository reviewRepository,
                           AuditLogService auditLog) {
        this.courseService = courseService;
        this.reviewService = reviewService;
        this.tutorApplicationService = tutorApplicationService;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.tutorApplicationRepository = tutorApplicationRepository;
        this.reviewRepository = reviewRepository;
        this.auditLog = auditLog;
    }

    // ---------- защита доступа ----------

    static void requireAdmin(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        if (!principal.isAdminLike()) {
            throw ApiException.forbidden("You do not have permission for this action.");
        }
    }

    static void requireSuperAdmin(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        if (principal.role() != Role.SUPER_ADMIN) {
            throw ApiException.forbidden("Only a SUPER_ADMIN can perform this action");
        }
    }

    // ---------- пользователи ----------

    @GetMapping("/api/v1/admin/users")
    public Page<AdminUserResponse> users(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) String role,
                                         @RequestParam(name = "blocked", required = false) Boolean blocked,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Role roleFilter = safeRole(role);
        Page<User> result = userRepository.searchAdmin(q == null ? null : q.trim().toLowerCase(),
                roleFilter, blocked, pageable);
        return result.map(this::toAdminUser);
    }

    /** PUT согласно admin.api.js (adminApi.blockUser зовёт apiClient.put). */
    @PutMapping("/api/v1/admin/users/{id}/block")
    public AdminUserResponse block(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        requireAdmin(principal);
        User target = userRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        if (target.getRole() == Role.SUPER_ADMIN && principal.role() != Role.SUPER_ADMIN) {
            throw ApiException.forbidden("Only a SUPER_ADMIN can block a SUPER_ADMIN");
        }
        target.setBlocked(true);
        auditLog.log(new AuditEntry(principal.id(), "USER_BLOCK", "USER", id.toString(), null));
        return toAdminUser(userRepository.save(target));
    }

    @PutMapping("/api/v1/admin/users/{id}/unblock")
    public AdminUserResponse unblock(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        requireAdmin(principal);
        User target = userRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        target.setBlocked(false);
        auditLog.log(new AuditEntry(principal.id(), "USER_UNBLOCK", "USER", id.toString(), null));
        return toAdminUser(userRepository.save(target));
    }

    @PutMapping("/api/v1/admin/users/{id}/role")
    public AdminUserResponse changeRole(@AuthenticationPrincipal UserPrincipal principal,
                                        @PathVariable UUID id,
                                        @RequestBody(required = false) RoleRequest request) {
        requireSuperAdmin(principal);
        if (request == null || request.role() == null) {
            throw new com.okututor.backend.common.error.FieldValidationException(Map.of("role", "role is required"));
        }
        Role newRole = safeRole(request.role());
        if (newRole == null) {
            throw ApiException.validation("Unknown role: " + request.role());
        }
        User target = userRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        boolean touchesAdminLike = target.getRole() == Role.ADMIN || target.getRole() == Role.SUPER_ADMIN
                || newRole == Role.ADMIN || newRole == Role.SUPER_ADMIN;
        if (touchesAdminLike && principal.role() != Role.SUPER_ADMIN) {
            throw ApiException.forbidden("Only a SUPER_ADMIN can manage admin roles");
        }
        target.setRole(newRole);
        auditLog.log(new AuditEntry(principal.id(), "ROLE_CHANGE", "USER", id.toString(), newRole.name()));
        return toAdminUser(userRepository.save(target));
    }

    @PutMapping("/api/v1/admin/users/{id}/verify")
    public AdminUserResponse verify(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        requireAdmin(principal);
        User target = userRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        target.setVerified(true);
        auditLog.log(new AuditEntry(principal.id(), "USER_VERIFY", "USER", id.toString(), null));
        return toAdminUser(userRepository.save(target));
    }

    // ---------- статистика ----------

    @GetMapping("/api/v1/admin/stats")
    public Map<String, Long> stats() {
        return Map.of(
                "total_users", userRepository.count(),
                "total_courses", courseService.countAll(),
                "total_reviews", reviewRepository.count(),
                "total_bookings", bookingRepository.count(),
                "pending_tutor_applications", tutorApplicationRepository.countByStatus(
                        com.okututor.backend.tutors.TutorApplication.Status.PENDING),
                "pending_enrollments", enrollmentRepository.countByStatus(
                        com.okututor.backend.enrollment.Enrollment.Status.PENDING));
    }

    // ---------- заявки репетиторов ----------

    @GetMapping("/api/v1/admin/tutors")
    public Page<TutorApplicationService.AdminApplicationRow> tutors(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return tutorApplicationService.list(status, page, size);
    }

    /** полная карточка заявки (поля submit + user email/avatar/created_at). 404, если нет. */
    @GetMapping("/api/v1/admin/tutors/{id}")
    public TutorApplicationService.AdminTutorApplicationDetail tutorDetail(@PathVariable UUID id) {
        return tutorApplicationService.detail(id);
    }

    @PostMapping("/api/v1/admin/tutors/{id}/approve")
    public TutorApplicationService.ApplicationResponse approveTutor(
            @AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        requireAdmin(principal);
        TutorApplicationService.ApplicationResponse response = tutorApplicationService.approve(id);
        auditLog.log(new AuditEntry(principal.id(), "TUTOR_APPROVE", "USER", id.toString(), null));
        return response;
    }

    @PostMapping("/api/v1/admin/tutors/{id}/reject")
    public TutorApplicationService.ApplicationResponse rejectTutor(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @RequestBody(required = false) ReasonRequest reason) {
        requireAdmin(principal);
        String reasonText = reason == null ? null : reason.reason();
        if (reasonText == null || reasonText.isBlank()) {
            throw new com.okututor.backend.common.error.FieldValidationException(
                    Map.of("reason", "reason is required"));
        }
        TutorApplicationService.ApplicationResponse response =
                tutorApplicationService.reject(id, reasonText);
        auditLog.log(new AuditEntry(principal.id(), "TUTOR_REJECT", "USER", id.toString(), reasonText));
        return response;
    }

    // ---------- модерация курсов ----------

    @GetMapping("/api/v1/admin/courses")
    public Page<com.okututor.backend.course.dto.CourseResponse> courses(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Course.Status parsed = status == null || status.isBlank() ? null : CourseService.parseStatus(status);
        return parsed == null
                ? courseService.allCourses(page, size)
                : courseService.byStatus(parsed, page, size);
    }

    @PostMapping("/api/v1/admin/courses/{id}/approve")
    public com.okututor.backend.course.dto.CourseResponse approveCourse(
            @AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        requireAdmin(principal);
        auditLog.log(new AuditEntry(principal.id(), "COURSE_APPROVE", "COURSE", id.toString(), null));
        return courseService.moderate(id, Course.Status.APPROVED, null);
    }

    @PostMapping("/api/v1/admin/courses/{id}/reject")
    public com.okututor.backend.course.dto.CourseResponse rejectCourse(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @RequestBody(required = false) ReasonRequest reason) {
        requireAdmin(principal);
        auditLog.log(new AuditEntry(principal.id(), "COURSE_REJECT", "COURSE", id.toString(),
                reason == null ? null : reason.reason()));
        return courseService.moderate(id, Course.Status.REJECTED, reason == null ? null : reason.reason());
    }

    // ---------- модерация отзывов ----------

    @GetMapping("/api/v1/admin/reviews")
    public Page<Map<String, Object>> reviews(@RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size) {
        return reviewService.listAll(page, size).map(this::toReviewMap);
    }

    @PostMapping("/api/v1/admin/reviews/{id}/hide")
    public ResponseEntity<Void> hideReview(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        requireAdmin(principal);
        auditLog.log(new AuditEntry(principal.id(), "REVIEW_HIDE", "REVIEW", id.toString(), null));
        reviewService.setHidden(id, true);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/admin/reviews/{id}/restore")
    public ResponseEntity<Void> restoreReview(@AuthenticationPrincipal UserPrincipal principal,
                                              @PathVariable UUID id) {
        requireAdmin(principal);
        auditLog.log(new AuditEntry(principal.id(), "REVIEW_RESTORE", "REVIEW", id.toString(), null));
        reviewService.setHidden(id, false);
        return ResponseEntity.noContent().build();
    }

    // ---------- вспомогательные методы ----------

    private AdminUserResponse toAdminUser(User user) {
        return new AdminUserResponse(user.getId(), user.getEmail(), user.getFullName(),
                user.getRole().name(), user.isVerified(), user.isBlocked(), user.getCreatedAt());
    }

    private Map<String, Object> toReviewMap(Review r) {
        return Map.of(
                "id", r.getId().toString(),
                "course_id", r.getCourse() != null ? r.getCourse().getId().toString() : "",
                "rating", r.getRating(),
                "comment", r.getComment() == null ? "" : r.getComment(),
                "student_id", r.getStudent() != null ? r.getStudent().getId().toString() : "",
                "student_name", r.getStudent() != null ? r.getStudent().getFullName() : "",
                "hidden", r.isHidden(),
                "created_at", r.getCreatedAt().toString());
    }

    private static Role safeRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Role.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
