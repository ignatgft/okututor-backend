-- Добавляем терминальный статус COMPLETED для enrollments (все занятия завершены)
ALTER TABLE enrollments DROP CONSTRAINT chk_enrollment_status;
ALTER TABLE enrollments ADD CONSTRAINT chk_enrollment_status CHECK (status IN
    ('PENDING', 'NEEDS_INFO', 'ACCEPTED', 'REJECTED',
     'SCHEDULE_PENDING', 'SCHEDULE_PROPOSED', 'SCHEDULED', 'CANCELLED', 'EXPIRED', 'COMPLETED'));
