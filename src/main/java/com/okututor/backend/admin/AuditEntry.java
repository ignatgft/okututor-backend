package com.okututor.backend.admin;

import java.util.UUID;

/** параметры аудита одним объектом вместо пяти позиционных аргументов. */
public record AuditEntry(
        UUID actorId,
        String action,
        String targetType,
        String targetId,
        String details
) {

    public static AuditEntry of(UUID actorId, String action, String targetType, UUID targetId) {
        return new AuditEntry(actorId, action, targetType,
                targetId == null ? null : targetId.toString(), null);
    }

    public AuditEntry withDetails(String details) {
        return new AuditEntry(actorId, action, targetType, targetId, details);
    }
}
