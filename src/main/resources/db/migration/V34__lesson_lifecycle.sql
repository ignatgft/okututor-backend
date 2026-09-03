-- Полный жизненный цикл занятия: новые статусы, фактические времена, заметки, pending-поля
-- Спека: SCHEDULED -> IN_PROGRESS -> COMPLETED + ответвления
-- RESCHEDULE_PENDING, FORMAT_CHANGE_PENDING, LOCATION_CHANGE_PENDING, DURATION_CHANGE_PENDING
-- STUDENT_NO_SHOW, TUTOR_NO_SHOW, ISSUE

ALTER TABLE lessons DROP CONSTRAINT IF EXISTS chk_lesson_status;
ALTER TABLE lessons ADD CONSTRAINT chk_lesson_status CHECK (status IN (
    'SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED',
    'RESCHEDULE_PENDING', 'FORMAT_CHANGE_PENDING', 'LOCATION_CHANGE_PENDING', 'DURATION_CHANGE_PENDING',
    'STUDENT_NO_SHOW', 'TUTOR_NO_SHOW', 'ISSUE'
));

-- фактические времена и участники действия
ALTER TABLE lessons
    ADD COLUMN IF NOT EXISTS actual_start timestamptz,
    ADD COLUMN IF NOT EXISTS actual_end timestamptz,
    ADD COLUMN IF NOT EXISTS started_by uuid REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS completed_by uuid REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS duration_minutes int,
    ADD COLUMN IF NOT EXISTS topic varchar(500),
    ADD COLUMN IF NOT EXISTS notes text,
    ADD COLUMN IF NOT EXISTS homework text,
    ADD COLUMN IF NOT EXISTS materials text,
    ADD COLUMN IF NOT EXISTS links text,
    ADD COLUMN IF NOT EXISTS attendance varchar(20);

-- pending поля для предложений (не применяются автоматически, требуют подтверждения ученика)
ALTER TABLE lessons
    ADD COLUMN IF NOT EXISTS pending_start_at timestamptz,
    ADD COLUMN IF NOT EXISTS pending_end_at timestamptz,
    ADD COLUMN IF NOT EXISTS pending_reason text,
    ADD COLUMN IF NOT EXISTS pending_format varchar(10),
    ADD COLUMN IF NOT EXISTS pending_location_type varchar(20),
    ADD COLUMN IF NOT EXISTS pending_location_address text,
    ADD COLUMN IF NOT EXISTS pending_location_details text,
    ADD COLUMN IF NOT EXISTS pending_duration_minutes int,
    ADD COLUMN IF NOT EXISTS pending_scope varchar(20),
    ADD COLUMN IF NOT EXISTS pending_proposed_by uuid REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS pending_proposed_at timestamptz;

-- индексы для быстрых выборок по статусам/серии
CREATE INDEX IF NOT EXISTS idx_lessons_status ON lessons(status);
CREATE INDEX IF NOT EXISTS idx_lessons_actual_start ON lessons(actual_start);
