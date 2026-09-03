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

    @PostMapping({"/api/v1/lessons/{id}/start", "/lessons/{id}/start"})
    public LessonDTO start(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                           @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User actor = currentUser(principal);
        Lesson lesson = lessonService.requireParticipantView(id, actor);
        if (!permissionEvaluator.canStart(lesson, actor.getId(), Instant.now()) && !isAdmin(actor)) {
            throw ApiException.forbidden("Cannot start this lesson");
        }
        lessonService.start(actor, id);
        Lesson updated = lessonService.requireById(id);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return lessonMapper.toDTO(updated, actor.getId(), locale, Instant.now());
    }

    @PostMapping({"/api/v1/lessons/{id}/complete", "/lessons/{id}/complete"})
    public LessonDTO complete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                              @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User actor = currentUser(principal);
        Lesson lesson = lessonService.requireParticipantView(id, actor);
        if (!permissionEvaluator.canComplete(lesson, actor.getId(), Instant.now()) && !isAdmin(actor)) {
            throw ApiException.forbidden("Cannot complete this lesson");
        }
        lessonService.complete(actor, id);
        Lesson updated = lessonService.requireById(id);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return lessonMapper.toDTO(updated, actor.getId(), locale, Instant.now());
    }

    @PostMapping({"/api/v1/lessons/{id}/student-no-show", "/lessons/{id}/student-no-show"})
    public LessonDTO studentNoShow(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                   @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User actor = currentUser(principal);
        Lesson lesson = lessonService.requireParticipantView(id, actor);
        if (!permissionEvaluator.canMarkStudentNoShow(lesson, actor.getId(), Instant.now()) && !isAdmin(actor)) {
            throw ApiException.forbidden("Cannot mark student no-show yet. Wait 15 minutes after start.");
        }
        Lesson updated = lessonService.markStudentNoShow(actor, id);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return lessonMapper.toDTO(updated, actor.getId(), locale, Instant.now());
    }

    @PostMapping({"/api/v1/lessons/{id}/tutor-no-show", "/lessons/{id}/tutor-no-show"})
    public LessonDTO tutorNoShow(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                 @RequestBody(required = false) Map<String,Object> payload,
                                 @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User actor = currentUser(principal);
        Lesson lesson = lessonService.requireParticipantView(id, actor);
        if (!permissionEvaluator.canMarkTutorNoShow(lesson, actor.getId(), Instant.now()) && !isAdmin(actor)) {
            throw ApiException.forbidden("Cannot mark tutor no-show yet. Wait 15 minutes after start.");
        }
        String reason = payload!=null ? (payload.get("reason")!=null? payload.get("reason").toString(): (payload.get("message")!=null?payload.get("message").toString():null)) : null;
        Lesson updated = lessonService.markTutorNoShow(actor, id, reason);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return lessonMapper.toDTO(updated, actor.getId(), locale, Instant.now());
    }

    @PostMapping({"/api/v1/lessons/{id}/issue", "/lessons/{id}/issue"})
    public LessonDTO issue(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                           @RequestBody(required = false) Map<String,Object> payload,
                           @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User actor = currentUser(principal);
        if (!permissionEvaluator.canReportIssue(lessonService.requireParticipantView(id, actor), actor.getId()) && !isAdmin(actor)) {
            throw ApiException.forbidden("Cannot report issue");
        }
        String reason = payload!=null ? (payload.get("reason")!=null? payload.get("reason").toString(): (payload.get("message")!=null?payload.get("message").toString(): (payload.get("comment")!=null?payload.get("comment").toString():null))) : null;
        Lesson updated = lessonService.reportIssue(actor, id, reason);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return lessonMapper.toDTO(updated, actor.getId(), locale, Instant.now());
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

    // ===== RESCHEDULE PROPOSE FLOW (pending, требует подтверждения ученика) =====
    @PostMapping({"/api/v1/lessons/{id}/reschedule/propose", "/lessons/{id}/reschedule/propose"})
    public LessonDTO proposeReschedule(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                       @RequestBody(required = false) Map<String,Object> payload,
                                       @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User actor = currentUser(principal);
        Instant start = extractInstant(payload, "newStartAt", "new_start_at", "startAt", "start_at", "start", "pendingStartAt");
        Instant end = extractInstant(payload, "newEndAt", "new_end_at", "endAt", "end_at", "end", "pendingEndAt");
        String tz = payload!=null && payload.get("timezone")!=null ? payload.get("timezone").toString() : null;
        if (start==null && payload!=null && payload.get("date")!=null && payload.get("time")!=null) {
            try {
                start = com.okututor.backend.booking.ScheduleParser.combine(payload.get("date").toString(), payload.get("time").toString(), tz);
                if (payload.get("duration_minutes")!=null) {
                    int dur=Integer.parseInt(payload.get("duration_minutes").toString());
                    end = start.plusSeconds(dur*60L);
                } else if (payload.get("durationMinutes")!=null) {
                    int dur=Integer.parseInt(payload.get("durationMinutes").toString());
                    end = start.plusSeconds(dur*60L);
                } else if (end==null) end = start.plusSeconds(3600);
            } catch(Exception e){ throw new FieldValidationException(Map.of("startAt", e.getMessage())); }
        }
        String reason = payload!=null && payload.get("reason")!=null ? payload.get("reason").toString() : (payload!=null && payload.get("comment")!=null ? payload.get("comment").toString(): (payload!=null && payload.get("pendingReason")!=null?payload.get("pendingReason").toString():null));
        String scope = payload!=null && payload.get("scope")!=null ? payload.get("scope").toString() : (payload!=null && payload.get("pendingScope")!=null?payload.get("pendingScope").toString():"SINGLE");
        if (start==null) throw new FieldValidationException(Map.of("newStartAt","newStartAt is required (ISO-8601 UTC or date+time)"));
        Lesson updated = lessonService.proposeReschedule(actor, id, start, end, reason, scope);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return lessonMapper.toDTO(updated, actor.getId(), locale, Instant.now());
    }
    @PostMapping({"/api/v1/lessons/{id}/reschedule/accept", "/lessons/{id}/reschedule/accept"})
    public LessonDTO acceptReschedule(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                      @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User actor = currentUser(principal);
        Lesson updated = lessonService.acceptReschedule(actor, id);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return lessonMapper.toDTO(updated, actor.getId(), locale, Instant.now());
    }
    @PostMapping({"/api/v1/lessons/{id}/reschedule/reject", "/lessons/{id}/reschedule/reject"})
    public LessonDTO rejectReschedule(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                      @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User actor = currentUser(principal);
        Lesson updated = lessonService.rejectReschedule(actor, id);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return lessonMapper.toDTO(updated, actor.getId(), locale, Instant.now());
    }

    // ===== FORMAT CHANGE =====
    @PostMapping({"/api/v1/lessons/{id}/format/propose", "/lessons/{id}/format/propose"})
    public LessonDTO proposeFormat(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                   @RequestBody(required = false) Map<String,Object> payload,
                                   @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User actor = currentUser(principal);
        String format = payload!=null && payload.get("format")!=null ? payload.get("format").toString() : null;
        String scope = payload!=null && payload.get("scope")!=null ? payload.get("scope").toString(): "SINGLE";
        LocationType lt = locationType(payload!=null?payload.get("location_type"):null);
        if (lt==null && payload!=null && payload.get("locationType")!=null) lt = locationType(payload.get("locationType"));
        String addr = payload!=null ? str(payload.get("location_address")!=null?payload.get("location_address"):payload.get("address")) : null;
        String det = payload!=null ? str(payload.get("location_details")!=null?payload.get("location_details"):payload.get("locationDetails")) : null;
        if (det==null && payload!=null) det = str(payload.get("location_comment"));
        Lesson updated = lessonService.proposeFormatChange(actor, id, format, lt, addr, det, scope);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return lessonMapper.toDTO(updated, actor.getId(), locale, Instant.now());
    }
    @PostMapping({"/api/v1/lessons/{id}/format/accept", "/lessons/{id}/format/accept"})
    public LessonDTO acceptFormat(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                  @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User actor = currentUser(principal);
        Lesson updated = lessonService.acceptFormatChange(actor, id);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return lessonMapper.toDTO(updated, actor.getId(), locale, Instant.now());
    }
    @PostMapping({"/api/v1/lessons/{id}/format/reject", "/lessons/{id}/format/reject"})
    public LessonDTO rejectFormat(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                  @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User actor = currentUser(principal);
        Lesson updated = lessonService.rejectFormatChange(actor, id);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return lessonMapper.toDTO(updated, actor.getId(), locale, Instant.now());
    }

    // ===== LOCATION CHANGE =====
    @PostMapping({"/api/v1/lessons/{id}/location/propose", "/lessons/{id}/location/propose"})
    public LessonDTO proposeLocation(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                     @RequestBody(required = false) Map<String,Object> payload,
                                     @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User actor = currentUser(principal);
        String scope = payload!=null && payload.get("scope")!=null ? payload.get("scope").toString(): "SINGLE";
        LocationType lt = locationType(payload!=null?payload.get("location_type"):null);
        if (lt==null && payload!=null && payload.get("locationType")!=null) lt = locationType(payload.get("locationType"));
        String addr = payload!=null ? str(payload.get("location_address")!=null?payload.get("location_address"):payload.get("address")) : null;
        String det = payload!=null ? str(payload.get("location_details")!=null?payload.get("location_details"): (payload.get("locationDetails")!=null?payload.get("locationDetails"):payload.get("location_comment"))) : null;
        Lesson updated = lessonService.proposeLocationChange(actor, id, lt, addr, det, scope);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return lessonMapper.toDTO(updated, actor.getId(), locale, Instant.now());
    }
    @PostMapping({"/api/v1/lessons/{id}/location/accept", "/lessons/{id}/location/accept"})
    public LessonDTO acceptLocation(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                    @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User actor = currentUser(principal);
        Lesson updated = lessonService.acceptLocationChange(actor, id);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return lessonMapper.toDTO(updated, actor.getId(), locale, Instant.now());
    }
    @PostMapping({"/api/v1/lessons/{id}/location/reject", "/lessons/{id}/location/reject"})
    public LessonDTO rejectLocation(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                    @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User actor = currentUser(principal);
        Lesson updated = lessonService.rejectLocationChange(actor, id);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return lessonMapper.toDTO(updated, actor.getId(), locale, Instant.now());
    }

    // ===== DURATION CHANGE =====
    @PostMapping({"/api/v1/lessons/{id}/duration/propose", "/lessons/{id}/duration/propose"})
    public LessonDTO proposeDuration(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                     @RequestBody(required = false) Map<String,Object> payload,
                                     @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User actor = currentUser(principal);
        String scope = payload!=null && payload.get("scope")!=null ? payload.get("scope").toString(): "SINGLE";
        Integer dur = null;
        if (payload!=null) {
            Object v = payload.get("duration_minutes");
            if (v==null) v = payload.get("durationMinutes");
            if (v==null) v = payload.get("duration");
            if (v!=null) try { dur = Integer.parseInt(v.toString()); } catch(Exception e){ dur=null; }
        }
        if (dur==null) throw new FieldValidationException(Map.of("duration_minutes","duration_minutes is required (30,45,60,90,120)"));
        Lesson updated = lessonService.proposeDurationChange(actor, id, dur, scope);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return lessonMapper.toDTO(updated, actor.getId(), locale, Instant.now());
    }
    @PostMapping({"/api/v1/lessons/{id}/duration/accept", "/lessons/{id}/duration/accept"})
    public LessonDTO acceptDuration(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                    @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User actor = currentUser(principal);
        Lesson updated = lessonService.acceptDurationChange(actor, id);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return lessonMapper.toDTO(updated, actor.getId(), locale, Instant.now());
    }
    @PostMapping({"/api/v1/lessons/{id}/duration/reject", "/lessons/{id}/duration/reject"})
    public LessonDTO rejectDuration(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                    @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User actor = currentUser(principal);
        Lesson updated = lessonService.rejectDurationChange(actor, id);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return lessonMapper.toDTO(updated, actor.getId(), locale, Instant.now());
    }

    // ===== POST-COMPLETION DETAILS =====
    @PostMapping({"/api/v1/lessons/{id}/details", "/lessons/{id}/details"})
    public LessonDTO updateDetails(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id,
                                   @RequestBody(required = false) Map<String,Object> payload,
                                   @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User actor = currentUser(principal);
        String topic = payload!=null ? str(payload.get("topic")) : null;
        String notes = payload!=null ? str(payload.get("notes")) : null;
        String hw = payload!=null ? str(payload.get("homework")!=null?payload.get("homework"):payload.get("home_work")) : null;
        String mats = payload!=null ? str(payload.get("materials")) : null;
        String links = payload!=null ? str(payload.get("links")) : null;
        var req = new LessonService.DetailsUpdateRequest(topic, notes, hw, mats, links);
        Lesson updated = lessonService.updateDetails(actor, id, req);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return lessonMapper.toDTO(updated, actor.getId(), locale, Instant.now());
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