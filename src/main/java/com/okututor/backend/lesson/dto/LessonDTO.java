package com.okututor.backend.lesson.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Полный DTO урока для Schedule API (спека §2).
 * Все поля в camelCase — JacksonConfig сериализует в snake_case.
 * permissions считаются на бэкенде.
 */
public record LessonDTO(
        UUID id,
        UUID courseId,
        String courseTitle,

        UUID tutorId,
        String tutorName,
        String tutorAvatar,

        UUID studentId,
        String studentName,

        Instant startAt,
        Instant endAt,
        String timezone,

        String status,
        String statusLabel,

        String format, // ONLINE | OFFLINE
        String meetingRoomId,

        boolean canJoin,
        boolean canCancel,
        boolean canReschedule,
        boolean canReview,

        String cancelledBy, // STUDENT | TUTOR | SYSTEM
        String cancelReason,

        Instant createdAt,
        Instant updatedAt
) {
}
