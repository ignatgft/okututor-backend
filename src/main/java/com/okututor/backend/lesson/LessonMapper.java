package com.okututor.backend.lesson;

import com.okututor.backend.lesson.dto.LessonDTO;
import com.okututor.backend.lesson.dto.LessonStatusLabelService;
import com.okututor.backend.user.User;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;

/**
 * Маппер Lesson -> LessonDTO с permissions и statusLabel
 */
@Component
public class LessonMapper {

    private final LessonPermissionEvaluator permissionEvaluator;
    private final LessonStatusLabelService labelService;

    public LessonMapper(LessonPermissionEvaluator permissionEvaluator,
                        LessonStatusLabelService labelService) {
        this.permissionEvaluator = permissionEvaluator;
        this.labelService = labelService;
    }

    public LessonDTO toDTO(Lesson lesson, Locale locale) {
        return toDTO(lesson, null, locale, Instant.now());
    }

    public LessonDTO toDTO(Lesson lesson, java.util.UUID viewerId, Locale locale, Instant now) {
        if (lesson == null) return null;
        Instant at = now != null ? now : Instant.now();
        Locale loc = locale != null ? locale : Locale.forLanguageTag("ru");

        // Resolve related entities (join fetch ensures no N+1)
        var course = lesson.getCourse();
        var teacher = lesson.getTeacher();
        var student = lesson.getStudent();
        var schedule = lesson.getSchedule();
        var booking = lesson.getBooking();

        String status = lesson.getStatus() != null ? lesson.getStatus().name() : "SCHEDULED";
        String statusLabel = labelService.labelFor(status, loc);

        // format ONLINE|OFFLINE
        String format = "ONLINE";
        if (schedule != null && schedule.getFormat() != null) {
            format = schedule.getFormat().name();
        } else if (lesson.getLocationType() != null) {
            format = "OFFLINE";
        } else if (course != null && course.getLocationType() != null) {
            format = course.getLocationType().name(); // ONLINE/OFFLINE
        }

        // timezone
        String timezone = "UTC";
        if (schedule != null && schedule.getTimezone() != null) {
            timezone = schedule.getTimezone();
        } else if (teacher != null && teacher.getTimezone() != null) {
            timezone = teacher.getTimezone();
        } else if (student != null && student.getTimezone() != null) {
            timezone = student.getTimezone();
        }

        // meetingRoomId
        String meetingRoomId = null;
        if (booking != null) {
            meetingRoomId = LiveKitTokenService.roomName(booking.getId());
        }

        boolean canJoin = permissionEvaluator.canJoin(lesson, at);
        boolean canCancel = permissionEvaluator.canCancel(lesson, at);
        boolean canReschedule = permissionEvaluator.canReschedule(lesson, at);
        boolean canReview = viewerId != null ? permissionEvaluator.canReview(lesson, viewerId) : false;

        String cancelledBy = null;
        if (lesson.getCancelledBy() != null) {
            User c = lesson.getCancelledBy();
            if (c.getId() != null) {
                if (c.getId().equals(lesson.getStudentId())) cancelledBy = "STUDENT";
                else if (c.getId().equals(lesson.getTeacherId())) cancelledBy = "TUTOR";
                else cancelledBy = "SYSTEM";
            }
        }

        String tutorAvatar = teacher != null ? teacher.getAvatarUrl() : null;
        // ensure tutorAvatar null if empty
        if (tutorAvatar != null && tutorAvatar.isBlank()) tutorAvatar = null;

        return new LessonDTO(
                lesson.getId(),
                course != null ? course.getId() : null,
                course != null ? course.getTitle() : lesson.getTitle(),
                lesson.getTeacherId(),
                teacher != null ? teacher.getFullName() : null,
                tutorAvatar,
                lesson.getStudentId(),
                student != null ? student.getFullName() : null,
                lesson.getStartAt(),
                lesson.getEndAt(),
                timezone,
                status,
                statusLabel,
                format,
                meetingRoomId,
                canJoin,
                canCancel,
                canReschedule,
                canReview,
                cancelledBy,
                lesson.getCancelReason(),
                lesson.getCreatedAt(),
                lesson.getUpdatedAt()
        );
    }

    public LessonDTO toDTO(Lesson lesson, Locale locale, Instant now) {
        return toDTO(lesson, lesson != null ? lesson.getStudentId() : null, locale, now);
    }
}
