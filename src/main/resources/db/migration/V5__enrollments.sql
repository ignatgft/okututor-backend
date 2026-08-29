-- Заявки студента на курс с небольшим автоматом состояний:
-- PENDING -> ACCEPTED | REJECTED | CANCELLED

CREATE TABLE enrollments (
    id                 uuid PRIMARY KEY,
    course_id          uuid NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    student_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    message            text,
    preferred_schedule text,
    status             varchar(20) NOT NULL DEFAULT 'PENDING',
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_enrollment_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED'))
);

-- только одна живая заявка на пару (студент, курс)
CREATE UNIQUE INDEX uq_enrollment_active
    ON enrollments(course_id, student_id) WHERE status IN ('PENDING', 'ACCEPTED');

CREATE INDEX idx_enrollments_student ON enrollments(student_id);
CREATE INDEX idx_enrollments_course ON enrollments(course_id);
