-- Домен репетитора: заявки + слоты доступности. Доп. поля профиля лежат в users,
-- потому что фронт рендерит их как часть объекта пользователя.

ALTER TABLE users ADD COLUMN experience_years int;
ALTER TABLE users ADD COLUMN education varchar(500);

CREATE TABLE availability_slots (
    id          uuid PRIMARY KEY,
    tutor_id    uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    weekday     varchar(10) NOT NULL,
    start_time  time NOT NULL,
    end_time    time NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_weekday CHECK (weekday IN
        ('Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday'))
);

CREATE INDEX idx_availability_tutor ON availability_slots(tutor_id);

CREATE TABLE tutor_applications (
    id                     uuid PRIMARY KEY,
    user_id                uuid NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    full_name              varchar(200),
    phone                  varchar(40),
    location               varchar(255),
    experience_years       int,
    experience_description text,
    education              varchar(500),
    subjects               text,
    languages              text,
    bio                    text,
    id_document_name       varchar(255),
    status                 varchar(20) NOT NULL DEFAULT 'PENDING',
    rejection_reason       text,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_application_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);
