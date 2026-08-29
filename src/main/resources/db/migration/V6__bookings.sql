-- Брони со строгим автоматом состояний и защитой от двойной брони:
-- единственная живая (PENDING/CONFIRMED) бронь на слот teacher+start и на
-- student+start обеспечивается частичными уникальными индексами на уровне БД.

CREATE TABLE bookings (
    id               uuid PRIMARY KEY,
    course_id        uuid NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    student_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    teacher_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    start_at         timestamptz NOT NULL,
    end_at           timestamptz NOT NULL,
    duration_minutes int NOT NULL,
    status           varchar(20) NOT NULL DEFAULT 'PENDING',
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_booking_status CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED', 'CANCELLED', 'COMPLETED'))
);

CREATE UNIQUE INDEX uq_booking_live_teacher ON bookings(teacher_id, start_at)
    WHERE status IN ('PENDING', 'CONFIRMED');

CREATE UNIQUE INDEX uq_booking_live_student ON bookings(student_id, start_at)
    WHERE status IN ('PENDING', 'CONFIRMED');

CREATE INDEX idx_bookings_student ON bookings(student_id);
CREATE INDEX idx_bookings_teacher ON bookings(teacher_id);
