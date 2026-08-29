-- optimistic locking: колонки версий для конкурентно обновляемых агрегатов
ALTER TABLE courses ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE support_tickets ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
