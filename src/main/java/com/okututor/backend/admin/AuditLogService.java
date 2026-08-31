package com.okututor.backend.admin;

import com.okututor.backend.user.User;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * неизменяемый след привилегированных действий (блокировки, смена роли, модерация).
 * Пишется асинхронно (notificationExecutor) и в независимой транзакции:
 * аудит не увеличивает latency HTTP-запроса и не блокируется его rollback-ом.
 */
@Service
public class AuditLogService {

    private final AuditLogRepository repository;

    public AuditLogService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Async("notificationExecutor")
    @Transactional
    public void log(AuditEntry entry) {
        repository.save(build(entry));
    }

    /** синхронная запись в той же транзакции (для таймлайна заявки): не «best-effort», молчит об ошибках. */
    @Transactional
    public void logSync(AuditEntry entry) {
        repository.saveAndFlush(build(entry));
    }

    private AuditLog build(AuditEntry entry) {
        AuditLog audit = new AuditLog();
        if (entry.actorId() != null) {
            // для внешнего ключа достаточно «голой» ссылки; не загружаем строку пользователя
            User ref = new User();
            ref.setId(entry.actorId());
            audit.setActor(ref);
        }
        audit.setAction(entry.action());
        audit.setTargetType(entry.targetType());
        audit.setTargetId(entry.targetId());
        String details = entry.details();
        audit.setDetails(details == null ? null : details.length() <= 2000 ? details : details.substring(0, 2000));
        audit.setOldValue(entry.oldValue());
        audit.setNewValue(entry.newValue());
        return audit;
    }
}
