-- Предложения расписания (история согласования). Independent от schedules:
-- каждая сторона может предложить/ответить, сохраняется полная история.

CREATE TABLE schedule_proposals (
    id               uuid PRIMARY KEY,
    application_id   uuid NOT NULL REFERENCES enrollments(id) ON DELETE CASCADE,
    schedule_id      uuid REFERENCES schedules(id) ON DELETE CASCADE,
    created_by       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status           varchar(20) NOT NULL DEFAULT 'PENDING',
    timezone         varchar(50) NOT NULL DEFAULT 'UTC',
    start_date       date NOT NULL,
    end_date         date NOT NULL,
    duration_minutes int NOT NULL,
    message          text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_schedule_proposal_status CHECK (status IN
        ('PENDING', 'ACCEPTED', 'REJECTED', 'SUPERSEDED', 'CANCELLED')),
    CONSTRAINT chk_schedule_proposal_duration CHECK (duration_minutes IN (30, 45, 60, 90, 120)),
    CONSTRAINT chk_schedule_proposal_dates CHECK (end_date >= start_date)
);

CREATE INDEX idx_schedule_proposals_application ON schedule_proposals(application_id, created_at DESC);
CREATE INDEX idx_schedule_proposals_schedule ON schedule_proposals(schedule_id);
CREATE INDEX idx_schedule_proposals_status ON schedule_proposals(application_id, status);

CREATE TABLE schedule_proposal_slots (
    id          uuid PRIMARY KEY,
    proposal_id uuid NOT NULL REFERENCES schedule_proposals(id) ON DELETE CASCADE,
    weekday     varchar(15) NOT NULL,
    start_time  time NOT NULL,
    end_time    time NOT NULL,
    CONSTRAINT chk_schedule_proposal_slot_weekday CHECK (weekday IN
        ('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY')),
    CONSTRAINT chk_schedule_proposal_slot_time CHECK (end_time > start_time)
);

CREATE INDEX idx_schedule_proposal_slots ON schedule_proposal_slots(proposal_id);