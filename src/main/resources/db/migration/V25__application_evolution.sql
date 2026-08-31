-- Эволюция Enrollment в полноценную заявку (CourseApplication):
-- + tutor_id (денормализация из course.teacher для прямых запросов),
-- + предпочтения ученика (формат / дни / время / частота / длительность),
-- + расширенная state machine PENDING → ACCEPTED → SCHEDULE_PENDING → SCHEDULE_PROPOSED → SCHEDULED.

ALTER TABLE enrollments
    ADD COLUMN tutor_id uuid REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN preferred_format varchar(10),
    ADD COLUMN preferred_days jsonb,
    ADD COLUMN preferred_start_time time,
    ADD COLUMN preferred_end_time time,
    ADD COLUMN frequency varchar(20),
    ADD COLUMN duration_minutes int,
    ADD COLUMN expires_at timestamptz;

-- backfill tutor_id из курса (старые заявки)
UPDATE enrollments e
SET tutor_id = c.teacher_id
FROM courses c
WHERE c.id = e.course_id AND e.tutor_id IS NULL;

CREATE INDEX idx_enrollments_tutor ON enrollments(tutor_id);
CREATE INDEX idx_enrollments_course_student_status ON enrollments(course_id, student_id, status);
CREATE INDEX idx_enrollments_student_status ON enrollments(student_id, status);

-- расширяем state machine
ALTER TABLE enrollments DROP CONSTRAINT chk_enrollment_status;
ALTER TABLE enrollments ADD CONSTRAINT chk_enrollment_status CHECK (status IN
    ('PENDING', 'NEEDS_INFO', 'ACCEPTED', 'REJECTED',
     'SCHEDULE_PENDING', 'SCHEDULE_PROPOSED', 'SCHEDULED', 'CANCELLED', 'EXPIRED'));

-- единственная «живая» заявка на пару (студент, курс) теперь включает
-- стадии согласования расписания (до SCHEDULED заявка активна)
DROP INDEX uq_enrollment_active;
CREATE UNIQUE INDEX uq_enrollment_active ON enrollments(course_id, student_id)
    WHERE status IN ('PENDING', 'NEEDS_INFO', 'ACCEPTED', 'SCHEDULE_PENDING', 'SCHEDULE_PROPOSED');

-- длительность занятия — только из допустимого множества
ALTER TABLE enrollments ADD CONSTRAINT chk_enrollment_duration
    CHECK (duration_minutes IS NULL OR duration_minutes IN (30, 45, 60, 90, 120));