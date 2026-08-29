-- Связь booking → enrollment (из какой заявки создано занятие)
ALTER TABLE bookings
    ADD COLUMN enrollment_id uuid REFERENCES enrollments(id) ON DELETE SET NULL;

CREATE INDEX idx_bookings_enrollment ON bookings(enrollment_id)
    WHERE enrollment_id IS NOT NULL;

-- Индекс для быстрой проверки факта присутствия студента в комнате
CREATE INDEX idx_meeting_started ON meeting_sessions(booking_id)
    WHERE started_at IS NOT NULL;
