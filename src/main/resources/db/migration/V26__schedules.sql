-- Регулярное расписание занятий по подтверждённой заявке.
-- Schedule привязан к application (enrollments) — ровно одно расписание на заявку.

CREATE TABLE schedules (
    id               uuid PRIMARY KEY,
    application_id   uuid NOT NULL UNIQUE REFERENCES enrollments(id) ON DELETE CASCADE,
    course_id        uuid NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    student_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tutor_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    format           varchar(10) NOT NULL DEFAULT 'ONLINE',
    start_date       date NOT NULL,
    end_date         date NOT NULL,
    timezone         varchar(50) NOT NULL DEFAULT 'UTC',
    frequency        varchar(20) NOT NULL DEFAULT 'WEEKLY',
    duration_minutes int NOT NULL,
    status           varchar(20) NOT NULL DEFAULT 'DRAFT',
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_schedule_format CHECK (format IN ('ONLINE', 'OFFLINE')),
    CONSTRAINT chk_schedule_frequency CHECK (frequency IN ('WEEKLY', 'BIWEEKLY', 'DAILY', 'CUSTOM')),
    CONSTRAINT chk_schedule_status CHECK (status IN ('DRAFT', 'PROPOSED', 'CONFIRMED', 'CANCELLED', 'COMPLETED')),
    CONSTRAINT chk_schedule_duration CHECK (duration_minutes IN (30, 45, 60, 90, 120)),
    CONSTRAINT chk_schedule_dates CHECK (end_date >= start_date)
);

CREATE INDEX idx_schedules_tutor ON schedules(tutor_id);
CREATE INDEX idx_schedules_student ON schedules(student_id);
CREATE INDEX idx_schedules_course ON schedules(course_id);
CREATE INDEX idx_schedules_status ON schedules(status);

-- недельные слоты расписания, а не одна строка-строка
CREATE TABLE schedule_slots (
    id          uuid PRIMARY KEY,
    schedule_id uuid NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,
    weekday     varchar(15) NOT NULL,
    start_time  time NOT NULL,
    end_time    time NOT NULL,
    CONSTRAINT chk_schedule_slot_weekday CHECK (weekday IN
        ('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY')),
    CONSTRAINT chk_schedule_slot_time CHECK (end_time > start_time)
);

CREATE INDEX idx_schedule_slots_schedule ON schedule_slots(schedule_id);