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

        // расширенные permissions
        boolean canStart,
        boolean canComplete,
        boolean canMarkStudentNoShow,
        boolean canMarkTutorNoShow,
        boolean canReportIssue,

        String cancelledBy, // STUDENT | TUTOR | SYSTEM
        String cancelReason,

        // жизненный цикл
        Instant actualStart,
        Instant actualEnd,
        Integer durationMinutes,
        UUID startedBy,
        UUID completedBy,

        // офлайн
        String locationType,
        String locationAddress,
        String locationDetails,

        // после завершения
        String topic,
        String notes,
        String homework,
        String materials,
        String links,
        String attendance,

        // pending предложения
        Instant pendingStartAt,
        Instant pendingEndAt,
        String pendingReason,
        String pendingFormat,
        String pendingLocationType,
        String pendingLocationAddress,
        String pendingLocationDetails,
        Integer pendingDurationMinutes,
        String pendingScope,
        UUID pendingProposedBy,
        Instant pendingProposedAt,

        Integer sequenceNumber,
        UUID scheduleId,
        UUID bookingId,

        Instant createdAt,
        Instant updatedAt
) {
}
