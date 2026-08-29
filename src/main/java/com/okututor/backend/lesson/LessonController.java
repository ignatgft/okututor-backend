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
        Lesson lesson = lessonService.requireById(id);
        if (!lesson.involves(viewer.getId())
                && viewer.getRole() != com.okututor.backend.user.Role.ADMIN
                && viewer.getRole() != com.okututor.backend.user.Role.SUPER_ADMIN) {
            throw ApiException.forbidden("Not your lesson");
        }
        return new LessonService.LessonResponse(
                lesson.getId(),
                lesson.getTitle(),
                counterpartOf(lesson, viewer),
                lesson.getStartAt(),
                lesson.getStatus().name(),
                lesson.getStatus() != Lesson.Status.CANCELLED
                        && lesson.getStatus() != Lesson.Status.COMPLETED,
                lesson.getBooking() != null ? lesson.getBooking().getId() : null);
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
        Lesson lesson = lessonService.create(tutor,
                uuid(payload.get("course_id")),
                studentId,
                str(payload.get("title")),
                instant(payload.get("start_at")));
        return Map.of("id", lesson.getId().toString(), "status", lesson.getStatus().name());
    }

    @PostMapping("/api/v1/lessons/{id}/start")
    public ResponseEntity<Void> start(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        requireParticipant(principal, id);
        lessonService.start(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/lessons/{id}/complete")
    public ResponseEntity<Void> complete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        requireParticipant(principal, id);
        lessonService.complete(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/lessons/{id}/cancel")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        requireParticipant(principal, id);
        lessonService.cancel(id);
        return ResponseEntity.ok().build();
    }

    // --- митинг-эндпоинты живут в MeetingController ---

    private void requireParticipant(UserPrincipal principal, UUID lessonId) {
        User viewer = currentUser(principal);
        Lesson lesson = lessonService.requireById(lessonId);
        boolean admin = viewer.getRole() == com.okututor.backend.user.Role.ADMIN
                || viewer.getRole() == com.okututor.backend.user.Role.SUPER_ADMIN;
        if (!lesson.involves(viewer.getId()) && !admin) {
            throw ApiException.forbidden("Not your lesson");
        }
    }

    private static String counterpartOf(Lesson lesson, User viewer) {
        boolean teacherSide = viewer != null && viewer.getId().equals(lesson.getTeacherId());
        User other = teacherSide ? lesson.getStudent() : lesson.getTeacher();
        return other != null ? other.getFullName() : null;
    }

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
}
