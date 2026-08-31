package com.okututor.backend.lesson;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.FieldValidationException;
import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LessonController {

    private final LessonService lessonService;
    private final UserService userService;

    public LessonController(LessonService lessonService, UserService userService) {
        this.lessonService = lessonService;
        this.userService = userService;
    }

    @GetMapping("/api/v1/lessons")
    public Page<LessonService.LessonResponse> list(@AuthenticationPrincipal UserPrincipal principal,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return lessonService.forUser(currentUser(principal), page, size);
    }

    @GetMapping("/api/v1/lessons/{id}")
    public LessonService.LessonResponse byId(@AuthenticationPrincipal UserPrincipal principal,
                                             @PathVariable UUID id) {
        User viewer = currentUser(principal);
        Lesson lesson = lessonService.requireParticipantView(id, viewer);
        return lessonService.toResponse(lesson, viewer.getId());
    }

    @PostMapping("/api/v1/lessons")
    @PreAuthorize("hasAnyRole('TUTOR','ADMIN','SUPER_ADMIN')")
    public Map<String, String> create(@AuthenticationPrincipal UserPrincipal principal,
                                      @RequestBody(required = false) Map<String, Object> payload) {
        User tutor = currentUser(principal);
        UUID studentId = uuid(payload == null ? null : payload.get("student_id"));
        if (studentId == null) {
            throw new FieldValidationException(Map.of("student_id", "student_id is required"));
        }
        String format = payload == null ? "ONLINE" : str(payload.get("format"));
        LocationType locationType = locationType(payload == null ? null : payload.get("location_type"));
        if ("OFFLINE".equalsIgnoreCase(format) && locationType == null) {
            throw new FieldValidationException(Map.of("location_type",
                    "location_type is required for OFFLINE lesson"));
        }
        Lesson lesson = lessonService.create(tutor,
                uuid(payload.get("course_id")),
                studentId,
                str(payload.get("title")),
                instant(payload.get("start_at")),
                locationType,
                str(payload.get("location_address")),
                str(payload.get("location_details")));
        return Map.of("id", lesson.getId().toString(), "status", lesson.getStatus().name());
    }

    @PostMapping("/api/v1/lessons/{id}/start")
    public ResponseEntity<Void> start(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        lessonService.start(currentUser(principal), id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/lessons/{id}/complete")
    public ResponseEntity<Void> complete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        lessonService.complete(currentUser(principal), id);
        return ResponseEntity.ok().build();
    }

    /** отмена занятия с причиной (spec §9): {message} → bookreason; зеркалит статус брони. */
    @PostMapping("/api/v1/lessons/{id}/cancel")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal UserPrincipal principal,
                                       @PathVariable UUID id,
                                       @RequestBody(required = false) CancelRequest request) {
        String reason = request == null ? null : request.message();
        lessonService.cancel(currentUser(principal), id, reason);
        return ResponseEntity.ok().build();
    }

    /** перенос занятия на другой интервал. */
    @PostMapping("/api/v1/lessons/{id}/reschedule")
    public LessonService.LessonResponse reschedule(@AuthenticationPrincipal UserPrincipal principal,
                                                   @PathVariable UUID id,
                                                   @RequestBody LessonService.RescheduleRequest request) {
        return lessonService.reschedule(currentUser(principal), id, request);
    }

    public record CancelRequest(String message) {}

    private User currentUser(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        return userService.requireById(principal.id());
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static UUID uuid(Object value) {
        try {
            return value == null ? null : UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            throw new FieldValidationException(Map.of("id", "Invalid UUID"));
        }
    }

    private static Instant instant(Object value) {
        if (value == null || value.toString().isBlank()) {
            return LocalDate.now(ZoneOffset.UTC).atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC);
        }
        try {
            return Instant.parse(value.toString());
        } catch (Exception e) {
            throw new FieldValidationException(Map.of("start_at", "Expected ISO-8601 instant"));
        }
    }

    private static LocationType locationType(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return LocationType.valueOf(value.toString().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new FieldValidationException(Map.of("location_type",
                    "location_type must be one of TUTOR_PLACE, STUDENT_PLACE, CENTER, OTHER"));
        }
    }
}