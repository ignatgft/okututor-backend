package com.okututor.backend.enrollment;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserService;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Алиасы для фронта: фронт (src/api/endpoints.js, students.api.js) бьёт в
 * /enrollments/{id}/request-info и /provide-info, тогда как канонические пути
 * в mapping.md — /applications/{id}/request-info и /submit-info.
 * Контроллер проксирует оба варианта на один и тот же workflow без дубля логики.
 */
@RestController
public class EnrollmentCompatibilityController {

    private final ApplicationWorkflowService workflowService;
    private final UserService userService;

    public EnrollmentCompatibilityController(ApplicationWorkflowService workflowService,
                                           UserService userService) {
        this.workflowService = workflowService;
        this.userService = userService;
    }

    @PostMapping("/api/v1/enrollments/{id}/request-info")
    @PreAuthorize("hasRole('TUTOR')")
    public EnrollmentService.EnrollmentResponse requestInfoAlias(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body) {
        String request = extract(body, "request", "question", "message");
        return workflowService.requestInfo(currentUser(principal), id,
                new ApplicationWorkflowService.RequestInfoRequest(request));
    }

    @PostMapping("/api/v1/enrollments/{id}/submit-info")
    @PreAuthorize("hasRole('STUDENT')")
    public EnrollmentService.EnrollmentResponse submitInfoAlias(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body) {
        String message = extract(body, "message", "request", "question");
        return workflowService.submitInfo(currentUser(principal), id,
                new ApplicationWorkflowService.SubmitInfoRequest(message));
    }

    @PostMapping("/api/v1/enrollments/{id}/provide-info")
    @PreAuthorize("hasRole('STUDENT')")
    public EnrollmentService.EnrollmentResponse provideInfoAlias(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body) {
        String message = extract(body, "message", "request", "question");
        return workflowService.submitInfo(currentUser(principal), id,
                new ApplicationWorkflowService.SubmitInfoRequest(message));
    }

    @GetMapping("/api/v1/enrollments/{id}/timeline")
    public java.util.List<ApplicationWorkflowService.TimelineItemResponse> timelineAlias(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        return workflowService.timeline(currentUser(principal), id);
    }

    private static String extract(Map<String, Object> body, String... keys) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        for (String k : keys) {
            Object v = body.get(k);
            if (v != null && !v.toString().isBlank()) {
                return v.toString();
            }
        }
        // fallback: first value if only one field
        for (Object v : body.values()) {
            if (v != null && !v.toString().isBlank()) {
                return v.toString();
            }
        }
        return null;
    }

    private User currentUser(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        return userService.requireById(principal.id());
    }
}
