-- Optimistic locking for Lesson pending operations (P0 race fix)
ALTER TABLE lessons ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
