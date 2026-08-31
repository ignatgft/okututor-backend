-- Уведомления: ссылка на сущность (entity_type + entity_id) для напоминаний,
-- таймлайна заявки и защиты от дубликатов. AuditLog: старое/новое значение.

ALTER TABLE notifications
    ADD COLUMN entity_type varchar(30),
    ADD COLUMN entity_id varchar(64);

CREATE INDEX idx_notifications_entity ON notifications(entity_type, entity_id)
    WHERE entity_type IS NOT NULL AND entity_id IS NOT NULL;

ALTER TABLE audit_logs
    ADD COLUMN old_value text,
    ADD COLUMN new_value text;