-- Курсы с модерацией. average_rating — денормализованный агрегат,
-- который поддерживает review-сервис.

CREATE TABLE courses (
    id              uuid PRIMARY KEY,
    teacher_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title           varchar(255) NOT NULL,
    subject         varchar(100) NOT NULL,
    category        varchar(100),
    description     text,
    price_per_hour  numeric(12,2) NOT NULL DEFAULT 0,
    currency        varchar(8) NOT NULL DEFAULT 'KGS',
    location_type   varchar(10) NOT NULL,
    group_size      varchar(12) NOT NULL,
    days            text,
    specific_days   text,
    experience      int,
    max_students    int DEFAULT 1,
    status          varchar(20) NOT NULL DEFAULT 'DRAFT',
    rejection_reason text,
    average_rating  numeric(3,2),
    reviews_count   int NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_course_location CHECK (location_type IN ('online', 'offline')),
    CONSTRAINT chk_course_group_size CHECK (group_size IN ('individual', 'group')),
    CONSTRAINT chk_course_status CHECK (status IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED'))
);

CREATE INDEX idx_courses_teacher ON courses(teacher_id);
CREATE INDEX idx_courses_subject ON courses(subject);
CREATE INDEX idx_courses_status ON courses(status);
