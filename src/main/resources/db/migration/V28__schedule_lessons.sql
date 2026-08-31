-- Конкретные занятия: привязка Booking/Lesson к расписанию, поля отмены,
-- end_at для Lesson, плюс фикс разрешённых статусов Booking (в т.ч. PROPOSED/RESCHEDULED/NO_SHOW,
-- которые уже есть в enum, но раньше не были в CHECK — латентный баг booking_proposals).

ALTER TABLE bookings
    ADD COLUMN schedule_id uuid REFERENCES schedules(id) ON DELETE SET NULL,
    ADD COLUMN cancelled_by uuid REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN cancel_reason text,
    ADD COLUMN cancelled_at timestamptz;

ALTER TABLE bookings DROP CONSTRAINT chk_booking_status;
ALTER TABLE bookings ADD CONSTRAINT chk_booking_status CHECK (status IN
    ('PENDING', 'CONFIRMED', 'REJECTED', 'CANCELLED', 'COMPLETED',
     'PROPOSED', 'RESCHEDULED', 'NO_SHOW'));

ALTER TABLE lessons
    ADD COLUMN end_at timestamptz,
    ADD COLUMN schedule_id uuid REFERENCES schedules(id) ON DELETE SET NULL,
    ADD COLUMN cancelled_by uuid REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN cancel_reason text,
    ADD COLUMN cancelled_at timestamptz;

-- индексы конфликтов по интервалам: teacher/student + время
CREATE INDEX idx_bookings_teacher_time ON bookings(teacher_id, start_at, end_at);
CREATE INDEX idx_bookings_student_time ON bookings(student_id, start_at, end_at);
CREATE INDEX idx_bookings_schedule ON bookings(schedule_id);

CREATE INDEX idx_lessons_teacher_time ON lessons(teacher_id, start_at, end_at);
CREATE INDEX idx_lessons_student_time ON lessons(student_id, start_at, end_at);
CREATE INDEX idx_lessons_schedule ON lessons(schedule_id);

-- доступность тьютора по дням недели (пересечение с предпочтениями ученика)
CREATE INDEX idx_availability_tutor_weekday ON availability_slots(tutor_id, weekday);