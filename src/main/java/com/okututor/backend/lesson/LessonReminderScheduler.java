package com.okututor.backend.lesson;

import com.okututor.backend.notification.NotificationRepository;
import com.okututor.backend.notification.NotificationService;
import com.okututor.backend.notification.NotificationType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Напоминания о предстоящих занятиях: за 24 часа и за 15 минут (spec §6).
 * Дедупликация — по (entity_type=LESSON, entity_id, type=LESSON_REMINDER,
 * payload.window), так что повторные прогоны не спамят.
 */
@Component
public class LessonReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(LessonReminderScheduler.class);

    private final LessonRepository lessonRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;

    public LessonReminderScheduler(LessonRepository lessonRepository,
                                   NotificationRepository notificationRepository,
                                   NotificationService notificationService) {
        this.lessonRepository = lessonRepository;
        this.notificationRepository = notificationRepository;
        this.notificationService = notificationService;
    }

    @Scheduled(fixedDelay = 5L * 60 * 1000, initialDelay = 60_000)
    @Transactional
    public void remind() {
        Instant now = Instant.now();
        remindWindow(now, now.plusSeconds(14 * 60), now.plusSeconds(16 * 60), ReminderWindow.FIFTEEN_MIN);
        remindWindow(now, now.plusSeconds(23L * 3600), now.plusSeconds(25L * 3600), ReminderWindow.DAY_AHEAD);
    }

    private void remindWindow(Instant now, Instant from, Instant to, ReminderWindow window) {
        List<Lesson> lessons = lessonRepository.findUpcomingForReminder(Lesson.Status.SCHEDULED, from, to);
        for (Lesson lesson : lessons) {
            if (notificationRepository.existsReminder("LESSON", lesson.getId().toString(),
                    NotificationType.LESSON_REMINDER, window.key())) {
                continue;
            }
            String message = window == ReminderWindow.FIFTEEN_MIN
                    ? "Занятие «%s» начнётся через 15 минут".formatted(titleOf(lesson))
                    : "Напоминание: занятие «%s» завтра".formatted(titleOf(lesson));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("lesson_id", lesson.getId().toString());
            payload.put("window", window.key());
            payload.put("scheduled_at", lesson.getStartAt() != null ? lesson.getStartAt().toString() : null);
            if (lesson.getBooking() != null) {
                payload.put("booking_id", lesson.getBooking().getId().toString());
            }
            if (lesson.getStudent() != null) {
                notificationService.notify(lesson.getStudentId(), message, NotificationType.LESSON_REMINDER,
                        "/student/schedule", payload, "LESSON", lesson.getId().toString());
            }
            if (lesson.getTeacher() != null) {
                notificationService.notify(lesson.getTeacherId(), message, NotificationType.LESSON_REMINDER,
                        "/tutor/schedule", payload, "LESSON", lesson.getId().toString());
            }
            log.debug("lesson reminder sent: lesson={} window={}", lesson.getId(), window.key());
        }
    }

    private enum ReminderWindow {
        FIFTEEN_MIN("15m"),
        DAY_AHEAD("24h");

        private final String key;

        ReminderWindow(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }
    }

    private static String titleOf(Lesson lesson) {
        return lesson.getTitle() != null && !lesson.getTitle().isBlank() ? lesson.getTitle() : "Занятие";
    }
}