-- Уроки и LiveKit meeting-сессии. Встреча привязана к BOOKING:
-- PgLesson входит через /bookings/{bookingId}/meeting/token и выходит через /meeting/end.

CREATE TABLE lessons (
    id          uuid PRIMARY KEY,
    course_id   uuid REFERENCES courses(id) ON DELETE SET NULL,
    booking_id  uuid REFERENCES bookings(id) ON DELETE CASCADE,
    teacher_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    student_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title       varchar(255),
    status      varchar(20) NOT NULL DEFAULT 'SCHEDULED',
    start_at    timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_lesson_status CHECK (status IN ('SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'))
);

CREATE INDEX idx_lessons_teacher ON lessons(teacher_id);
CREATE INDEX idx_lessons_student ON lessons(student_id);

CREATE TABLE meeting_sessions (
    id              uuid PRIMARY KEY,
    booking_id      uuid NOT NULL UNIQUE REFERENCES bookings(id) ON DELETE CASCADE,
    room_name       varchar(120) NOT NULL,
    started_at      timestamptz,
    ended_at        timestamptz,
    token_issued_at timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);
