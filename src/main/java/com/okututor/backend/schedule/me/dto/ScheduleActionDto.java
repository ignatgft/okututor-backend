package com.okututor.backend.schedule.me.dto;

import java.util.Map;
import java.util.UUID;

/**
 * Структурированные действия для /schedule/me/actions (спека §3)
 */
public record ScheduleActionDto(
        String id,
        String type,
        String title,
        String description,
        String priority, // high | medium | low
        UUID relatedLessonId,
        UUID relatedBookingId,
        ActionRef primaryAction,
        ActionRef secondaryAction,
        Map<String, Object> meta
) {
    public record ActionRef(
            String label,
            String method, // GET | POST | PATCH
            String endpoint
    ) {}
}
