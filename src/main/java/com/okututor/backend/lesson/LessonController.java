package com.okututor.backend.lesson;

import com.okututor.backend.booking.BookingProposalService;
import com.okututor.backend.booking.dto.BookingProposalRequest;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.FieldValidationException;
import com.okututor.backend.lesson.dto.LessonDTO;
import com.okututor.backend.lesson.dto.LessonStatusLabelService;
import com.okututor.backend.review.ReviewService;
import com.okututor.backend.schedule.me.dto.ScheduleMeDtos;
import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Locale;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LessonController {

    private final LessonService lessonService;
    private final UserService userService;
    private final LessonMapper lessonMapper;
    private final LessonPermissionEvaluator permissionEvaluator;
    private final MeetingService meetingService;
    private final ReviewService reviewService;
    private final BookingProposalService bookingProposalService;
    private final LessonStatusLabelService labelService;

    public LessonController(LessonService lessonService, UserService userService,
                            LessonMapper lessonMapper,
                            LessonPermissionEvaluator permissionEvaluator,
                            MeetingService meetingService,
                            ReviewService reviewService,
                            BookingProposalService bookingProposalService,
                            LessonStatusLabelService labelService) {
        this.lessonService = lessonService;
        this.userService = userService;
        this.lessonMapper = lessonMapper;
        this.permissionEvaluator = permissionEvaluator;
        this.meetingService = meetingService;
        this.reviewService = reviewService;
        this.bookingProposalService = bookingProposalService;
        this.labelService = labelService;
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

    /** отмена занятия с причиной (spec §9): {message} → bookreason; зеркалит статус брони.
     *  Поддерживает как /api/v1 так и без префикса для спеки.
     *  Возвращает LessonDTO с актуальными permissions (canCancel и т.д.).
     */
    @PostMapping({"/api/v1/lessons/{id}/cancel", "/lessons/{id}/cancel"})
    public LessonDTO cancel(@AuthenticationPrincipal UserPrincipal principal,
                            @PathVariable UUID id,
                            @RequestBody(required = false) Map<String, Object> payload,
                            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User actor = currentUser(principal);
        Lesson lesson = lessonService.requireParticipantView(id, actor);
        Instant now = Instant.now();
        // permission check (unless admin)
        if (!permissionEvaluator.canCancel(lesson, now) && !isAdmin(actor)) {
            throw ApiException.forbidden("Cancellation not allowed at this time");
        }
        String reason = null;
        if (payload != null) {
            Object r = payload.get("reason");
            if (r == null) r = payload.get("message");
            if (r == null) r = payload.get("cancelReason");
            if (r == null) r = payload.get("cancel_reason");
            if (r != null) reason = r.toString();
        }
        lessonService.cancel(actor, id, reason);
        Lesson updated = lessonService.requireById(id);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return lessonMapper.toDTO(updated, actor.getId(), locale, Instant.now());
    }

    /** перенос занятия на другой интервал — поддерживает instant-перенос и proposal (spec). */
    @PostMapping({"/api/v1/lessons/{id}/reschedule", "/lessons/{id}/reschedule"})
    public Object reschedule(@AuthenticationPrincipal UserPrincipal principal,
                             @PathVariable UUID id,
                             @RequestBody(required = false) Map<String, Object> payload,
                             @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User actor = currentUser(principal);
        Lesson lesson = lessonService.requireParticipantView(id, actor);
        if (!permissionEvaluator.canReschedule(lesson, Instant.now()) && !isAdmin(actor)) {
            throw ApiException.forbidden("Reschedule not allowed at this time");
        }
        // payload может содержать start_at/startAt/end_at/endAt/timezone
        Instant start = extractInstant(payload, "startAt", "start_at", "startAtIso", "start");
        Instant end = extractInstant(payload, "endAt", "end_at", "end");
        String tz = payload != null && payload.get("timezone") != null ? payload.get("timezone").toString() : null;
        // если переданы date+time отдельно
        if (start == null && payload != null && payload.get("date") != null && payload.get("time") != null) {
            try {
                start = com.okututor.backend.booking.ScheduleParser.combine(
                        payload.get("date").toString(),
                        payload.get("time").toString(),
                        tz);
                if (payload.get("duration_minutes") != null) {
                    int dur = Integer.parseInt(payload.get("duration_minutes").toString());
                    end = start.plusSeconds(dur * 60L);
                } else if (end == null) {
                    end = start.plusSeconds(3600);
                }
            } catch (Exception e) {
                throw new FieldValidationException(Map.of("startAt", e.getMessage()));
            }
        }
        if (start == null) {
            throw new FieldValidationException(Map.of("startAt", "startAt is required (ISO-8601 UTC)"));
        }
        if (end == null) end = start.plusSeconds(3600);

        // если урок привязан к брони — создаём proposal (предложение нового времени), иначе прямой перенос
        if (lesson.getBooking() != null) {
            try {
                var req = new BookingProposalRequest(start, end);
                var proposal = bookingProposalService.createProposal(lesson.getBooking(), actor, req);
                return proposal;
            } catch (ApiException e) {
                // fallback к прямому переносу если proposal нельзя (например уже есть active)
                throw e;
            }
        }
        LessonService.RescheduleRequest req = new LessonService.RescheduleRequest(start, end);
        LessonService.LessonResponse resp = lessonService.reschedule(actor, id, req);
        // маппим к DTO для консистентности, но сохраняем совместимость — если фронт ждал LessonResponse, DTO содержит те же поля плюс больше
        Lesson updated = lessonService.requireById(id);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return lessonMapper.toDTO(updated, actor.getId(), locale, Instant.now());
    }

    /** вход в видеоурок — возвращает meetingUrl / roomId (spec POST /lessons/{id}/join) */
    @PostMapping({"/api/v1/lessons/{id}/join", "/lessons/{id}/join"})
    public ScheduleMeDtos.JoinResponse join(@AuthenticationPrincipal UserPrincipal principal,
                                            @PathVariable UUID id) {
        User actor = currentUser(principal);
        Lesson lesson = lessonService.requireParticipantView(id, actor);
        if (!permissionEvaluator.canJoin(lesson, Instant.now())) {
            throw ApiException.forbidden(com.okututor.backend.common.error.ErrorCodes.MEETING_NOT_AVAILABLE,
                    "Join not allowed at this time. Room opens 15 minutes before start.");
        }
        if (lesson.getBooking() == null) {
            throw ApiException.notFound(com.okututor.backend.common.error.ErrorCodes.MEETING_NOT_AVAILABLE,
                    "This lesson has no meeting room (no booking)");
        }
        var token = meetingService.token(actor, lesson.getBooking().getId());
        String roomId = token.room_name() != null ? token.room_name() : com.okututor.backend.lesson.LiveKitTokenService.roomName(lesson.getBooking().getId());
        String meetingUrl = token.server_url() != null ? token.server_url() + "/" + roomId : null;
        return new ScheduleMeDtos.JoinResponse(meetingUrl, roomId, token.server_url(), token.token(), roomId);
    }

    /** создание отзыва привязанного к уроку (spec POST /lessons/{id}/review) */
    @PostMapping({"/api/v1/lessons/{id}/review", "/lessons/{id}/review"})
    public ReviewService.ReviewResponse review(@AuthenticationPrincipal UserPrincipal principal,
                                               @PathVariable UUID id,
                                               @RequestBody(required = false) Map<String, Object> payload) {
        User actor = currentUser(principal);
        Lesson lesson = lessonService.requireParticipantView(id, actor);
        // только студент может оставить отзыв
        if (!actor.getId().equals(lesson.getStudentId()) && !isAdmin(actor)) {
            throw ApiException.forbidden(com.okututor.backend.common.error.ErrorCodes.REVIEW_NOT_ALLOWED,
                    "Only the student can leave a review");
        }
        if (!permissionEvaluator.canReview(lesson, actor.getId())) {
            throw ApiException.forbidden(com.okututor.backend.common.error.ErrorCodes.REVIEW_NOT_ALLOWED,
                    "You can review only after the lesson is completed and attended");
        }
        if (lesson.getCourse() == null) {
            throw ApiException.validation("Lesson has no course");
        }
        Integer rating = null;
        String comment = null;
        if (payload != null) {
            Object r = payload.get("rating");
            if (r != null) {
                try { rating = Integer.parseInt(r.toString()); } catch (Exception e) { rating = null; }
            }
            Object c = payload.get("comment");
            if (c == null) c = payload.get("message");
            if (c != null) comment = c.toString();
        }
        if (rating == null) {
            throw new FieldValidationException(Map.of("rating", "Rating is required (1..5)"));
        }
        // если есть booking — привязываем отзыв к booking
        if (lesson.getBooking() != null) {
            return reviewService.createForBooking(actor, lesson.getCourse().getId(),
                    lesson.getBooking().getId(), rating, comment);
        } else {
            return reviewService.create(actor, lesson.getCourse().getId(), rating, comment);
        }
    }

    /** Детальный DTO для фронта Schedule (LessonDTO с permissions) */
    @GetMapping({"/api/v1/lessons/{id}/dto", "/lessons/{id}/dto"})
    public LessonDTO dto(@AuthenticationPrincipal UserPrincipal principal,
                         @PathVariable UUID id,
                         @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User viewer = currentUser(principal);
        Lesson lesson = lessonService.requireParticipantView(id, viewer);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return lessonMapper.toDTO(lesson, viewer.getId(), locale, Instant.now());
    }

    public record CancelRequest(String message) {}

    private Instant extractInstant(Map<String, Object> payload, String... keys) {
        if (payload == null) return null;
        for (String k : keys) {
            Object v = payload.get(k);
            if (v != null && !v.toString().isBlank()) {
                try {
                    return Instant.parse(v.toString());
                } catch (Exception e) {
                    // try as epoch millis?
                }
            }
        }
        return null;
    }

    private static boolean isAdmin(User u) {
        return u != null && (u.getRole() == com.okututor.backend.user.Role.ADMIN
                || u.getRole() == com.okututor.backend.user.Role.SUPER_ADMIN);
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