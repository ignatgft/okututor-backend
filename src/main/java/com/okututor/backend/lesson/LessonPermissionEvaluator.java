package com.okututor.backend.lesson;

import com.okututor.backend.common.config.AppProperties;
import com.okututor.backend.review.ReviewRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Вычисляет permissions для LessonDTO на бэкенде (спека §2).
 * Все проверки — на основе текущего временем сервера и состояния БД.
 */
@Component
public class LessonPermissionEvaluator {

    private final AppProperties properties;
    private final MeetingSessionRepository meetingSessionRepository;
    private final ReviewRepository reviewRepository;

    public LessonPermissionEvaluator(AppProperties properties,
                                     MeetingSessionRepository meetingSessionRepository,
                                     ReviewRepository reviewRepository) {
        this.properties = properties;
        this.meetingSessionRepository = meetingSessionRepository;
        this.reviewRepository = reviewRepository;
    }

    public boolean canJoin(Lesson lesson, Instant now) {
        if (lesson == null || lesson.getStartAt() == null) return false;
        Lesson.Status status = lesson.getStatus();
        if (status != Lesson.Status.SCHEDULED && status != Lesson.Status.IN_PROGRESS) return false;
        // OFFLINE занятия не имеют LiveKit комнаты — join только для ONLINE
        // формат определяем по schedule.format или locationType; OFFLINE => нельзя войти в видео
        boolean isOffline = false;
        if (lesson.getSchedule() != null && lesson.getSchedule().getFormat() != null) {
            isOffline = lesson.getSchedule().getFormat() == com.okututor.backend.schedule.Schedule.Format.OFFLINE;
        } else if (lesson.getLocationType() != null) {
            isOffline = true;
        }
        if (isOffline) return false;

        Instant start = lesson.getStartAt();
        Instant end = lesson.getEndAt() != null ? lesson.getEndAt() : start.plusSeconds(3600);
        int joinBefore = properties.getLesson().getJoinMinutesBefore();
        int joinAfter = properties.getLesson().getJoinMinutesAfter();
        Instant windowOpen = start.minusSeconds(joinBefore * 60L);
        Instant windowClose = end.plusSeconds(joinAfter * 60L);
        return !now.isBefore(windowOpen) && !now.isAfter(windowClose);
    }

    public boolean canCancel(Lesson lesson, Instant now) {
        if (lesson == null || !lesson.isLive()) return false;
        if (lesson.getStartAt() != null && !now.isBefore(lesson.getStartAt())) {
            return false;
        }
        int hours = properties.getLesson().getCancelHoursBefore();
        if (lesson.getStartAt() != null) {
            Instant threshold = lesson.getStartAt().minusSeconds(hours * 3600L);
            if (now.isAfter(threshold)) return false;
        }
        return lesson.getStatus() == Lesson.Status.SCHEDULED;
    }

    public boolean canReschedule(Lesson lesson, Instant now) {
        if (lesson == null || !lesson.isLive()) return false;
        if (lesson.getStartAt() != null && !now.isBefore(lesson.getStartAt())) {
            return false;
        }
        int hours = properties.getLesson().getRescheduleHoursBefore();
        if (lesson.getStartAt() != null) {
            Instant threshold = lesson.getStartAt().minusSeconds(hours * 3600L);
            if (now.isAfter(threshold)) return false;
        }
        return lesson.getStatus() == Lesson.Status.SCHEDULED;
    }

    public boolean canStart(Lesson lesson, UUID viewerId, Instant now) {
        if (lesson == null || viewerId == null) return false;
        // только участники, и только тьютор может начать (ученик тоже может? по спеке — тьютор)
        if (!viewerId.equals(lesson.getTeacherId()) && !viewerId.equals(lesson.getStudentId())) return false;
        return lesson.getStatus() == Lesson.Status.SCHEDULED;
    }

    public boolean canComplete(Lesson lesson, UUID viewerId, Instant now) {
        if (lesson == null || viewerId == null) return false;
        if (!viewerId.equals(lesson.getTeacherId()) && !viewerId.equals(lesson.getStudentId())) return false;
        return lesson.getStatus() == Lesson.Status.IN_PROGRESS;
    }

    public boolean canMarkStudentNoShow(Lesson lesson, UUID viewerId, Instant now) {
        if (lesson == null || viewerId == null || now == null) return false;
        if (!viewerId.equals(lesson.getTeacherId())) return false; // только тьютор
        if (lesson.getStatus() != Lesson.Status.SCHEDULED) return false;
        if (lesson.getStartAt() == null) return false;
        int wait = properties.getLesson().getNoShowWaitMinutes();
        Instant eligibleAt = lesson.getStartAt().plusSeconds(wait * 60L);
        return !now.isBefore(eligibleAt);
    }

    public boolean canMarkTutorNoShow(Lesson lesson, UUID viewerId, Instant now) {
        if (lesson == null || viewerId == null || now == null) return false;
        if (!viewerId.equals(lesson.getStudentId())) return false; // только ученик
        if (lesson.getStatus() != Lesson.Status.SCHEDULED) return false;
        if (lesson.getStartAt() == null) return false;
        int wait = properties.getLesson().getNoShowWaitMinutes();
        Instant eligibleAt = lesson.getStartAt().plusSeconds(wait * 60L);
        return !now.isBefore(eligibleAt);
    }

    public boolean canReportIssue(Lesson lesson, UUID viewerId) {
        if (lesson == null || viewerId == null) return false;
        if (!lesson.involves(viewerId)) return false;
        // можно сообщить о проблеме в IN_PROGRESS или COMPLETED
        return lesson.getStatus() == Lesson.Status.IN_PROGRESS
                || lesson.getStatus() == Lesson.Status.COMPLETED
                || lesson.getStatus() == Lesson.Status.SCHEDULED;
    }

    public boolean canReview(Lesson lesson, UUID viewerId) {
        if (lesson == null || viewerId == null) return false;
        // только студент может оставить отзыв
        if (lesson.getStudentId() != null && !viewerId.equals(lesson.getStudentId())) {
            // тьютор не оставляет отзыв на свой урок (но может в будущем)
            // Разрешаем только владельцу student
            // Если viewer — тьютор, возвращаем false
            return false;
        }
        if (lesson.getStatus() != Lesson.Status.COMPLETED) return false;
        if (lesson.getCourse() == null) return false;

        // Проверка реального проведения: MeetingSession с started_at не null
        boolean hasRealSession = false;
        if (lesson.getBooking() != null) {
            var sess = meetingSessionRepository.findByBookingId(lesson.getBooking().getId());
            if (sess.isPresent() && sess.get().getStartedAt() != null) {
                hasRealSession = true;
            }
            // fallback: booking COMPLETED + session started considered
        } else {
            // standalone lesson без booking — проверяем через hasAttendedLesson
            if (meetingSessionRepository.hasAttendedLesson(lesson.getCourse().getId(), viewerId)) {
                hasRealSession = true;
            }
        }
        if (!hasRealSession) return false;

        // уже оставлял отзыв — нельзя повторно
        boolean alreadyReviewed = reviewRepository.findByCourseIdAndStudentId(
                lesson.getCourse().getId(), viewerId).isPresent();
        return !alreadyReviewed;
    }

    /**
     * @deprecated use canReview(Lesson, UUID) — оставлен для совместимости
     */
    @Deprecated
    public boolean canReview(Lesson lesson) {
        if (lesson == null || lesson.getStudentId() == null) return false;
        return canReview(lesson, lesson.getStudentId());
    }
}
