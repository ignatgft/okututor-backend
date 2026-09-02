package com.okututor.backend.schedule.me.dto;

import com.okututor.backend.lesson.dto.LessonDTO;

import java.util.List;
import java.util.UUID;

/**
 * Контракты ответов для /schedule/me/* эндпоинтов
 */
public final class ScheduleMeDtos {

    private ScheduleMeDtos() {}

    public record NextLessonResponse(
            LessonDTO lesson,
            Long countdownSeconds,
            Boolean canJoin,
            String meetingRoomId
    ) {}

    public record DayResponse(
            String date,
            List<LessonDTO> lessons,
            int count
    ) {}

    public record WeekDayGroup(
            String date,
            List<LessonDTO> lessons,
            int count
    ) {}

    public record WeekResponse(
            String startDate,
            String endDate,
            List<WeekDayGroup> days,
            int totalCount
    ) {}

    public record MonthDaySummary(
            String date,
            int count,
            List<String> statuses
    ) {}

    public record MonthResponse(
            int year,
            int month,
            List<MonthDaySummary> days,
            int totalCount
    ) {}

    public record ActionsResponse(
            List<ScheduleActionDto> actions
    ) {}

    // For lesson action responses
    public record JoinResponse(
            String meetingUrl,
            String roomId,
            String serverUrl,
            String token,
            String roomName
    ) {}

    public record CancelRequest(
            String reason,
            String message
    ) {
        public String effectiveReason() {
            if (reason != null && !reason.isBlank()) return reason;
            if (message != null && !message.isBlank()) return message;
            return null;
        }
    }

    public record RescheduleRequest(
            String startAt,
            String endAt,
            String timezone
    ) {}

    public record ReviewRequest(
            Integer rating,
            String comment
    ) {}
}
