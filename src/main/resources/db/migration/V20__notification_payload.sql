-- Structured payload уведомлений (JSONB, null-совместимо со старыми записями).
ALTER TABLE notifications
    ADD COLUMN payload jsonb;

COMMENT ON COLUMN notifications.payload IS
    'Structured context: {"enrollment_id","booking_id","course_id","scheduled_at","duration_minutes"}. Null для старых записей.';
