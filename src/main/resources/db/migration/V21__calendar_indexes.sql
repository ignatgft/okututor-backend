-- Календарь: композитные индексы для диапазонных выборок виду
--   WHERE student_id = ? AND start_at >= ? AND start_at < ?
--   WHERE teacher_id = ? AND start_at >= ? AND start_at < ?
--   WHERE status = ?     AND start_at >= ? ...
-- (частичные уникальные индексы uq_booking_live_* уже покрывают живые статусы по start_at).

CREATE INDEX idx_bookings_calendar_student ON bookings(student_id, start_at);
CREATE INDEX idx_bookings_calendar_teacher ON bookings(teacher_id, start_at);
CREATE INDEX idx_bookings_status_start     ON bookings(status, start_at);

CREATE INDEX idx_lessons_calendar_student  ON lessons(student_id, start_at);
CREATE INDEX idx_lessons_calendar_teacher  ON lessons(teacher_id, start_at);
CREATE INDEX idx_lessons_status_start      ON lessons(status, start_at);
