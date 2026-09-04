-- Схлопывание четырёх pending-статусов урока в один CHANGE_PENDING + pending_change_type.
-- RESCHEDULE_PENDING / FORMAT_CHANGE_PENDING / LOCATION_CHANGE_PENDING / DURATION_CHANGE_PENDING
-- различались только типом предложенного изменения — это одно состояние «предложено изменение».

-- 1. новая колонка типа предложенного изменения
ALTER TABLE lessons
    ADD COLUMN IF NOT EXISTS pending_change_type varchar(20);

-- 2. перенос данных: старый статус -> CHANGE_PENDING + тип
UPDATE lessons SET pending_change_type = 'RESCHEDULE',  status = 'CHANGE_PENDING' WHERE status = 'RESCHEDULE_PENDING';
UPDATE lessons SET pending_change_type = 'FORMAT',      status = 'CHANGE_PENDING' WHERE status = 'FORMAT_CHANGE_PENDING';
UPDATE lessons SET pending_change_type = 'LOCATION',    status = 'CHANGE_PENDING' WHERE status = 'LOCATION_CHANGE_PENDING';
UPDATE lessons SET pending_change_type = 'DURATION',    status = 'CHANGE_PENDING' WHERE status = 'DURATION_CHANGE_PENDING';

-- 3. новый CHECK-констрейнт без старых значений
ALTER TABLE lessons DROP CONSTRAINT IF EXISTS chk_lesson_status;
ALTER TABLE lessons ADD CONSTRAINT chk_lesson_status CHECK (status IN (
    'SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED',
    'CHANGE_PENDING',
    'STUDENT_NO_SHOW', 'TUTOR_NO_SHOW', 'ISSUE'
));
