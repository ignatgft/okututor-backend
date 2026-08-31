package com.okututor.backend.admin;

import java.util.UUID;

/** параметры аудита одним объектом вместо пяти позиционных аргументов. */
public record AuditEntry(
        UUID actorId,
        String action,
        String targetType,
        String targetId,
        String details,
        String oldValue,
        String newValue
) {

    public AuditEntry {
        // детали обрезаются в AuditLogService; здесь только нормализация отсутствующих значений
    }

    /** устаревший 5-аргументный конструктор (детали без old/new). */
    public AuditEntry(UUID actorId, String action, String targetType, String targetId, String details) {
        this(actorId, action, targetType, targetId, details, null, null);
    }

    public static AuditEntry of(UUID actorId, String action, String targetType, UUID targetId) {
        return new AuditEntry(actorId, action, targetType,
                targetId == null ? null : targetId.toString(), null, null, null);
    }

    public AuditEntry withDetails(String details) {
        return new AuditEntry(actorId, action, targetType, targetId, details, oldValue, newValue);
    }

    public AuditEntry withValues(String oldValue, String newValue) {
        return new AuditEntry(actorId, action, targetType, targetId, details, oldValue, newValue);
    }
}
