-- Базовые таблицы идентичности. Остальные домены добавляют свои миграции.

CREATE TABLE users (
    id              uuid PRIMARY KEY,
    email           varchar(255) NOT NULL UNIQUE,
    password_hash   varchar(255),
    first_name      varchar(100),
    last_name       varchar(100),
    bio             text,
    phone           varchar(40),
    location        varchar(255),
    avatar_url      text,
    role            varchar(20)  NOT NULL,
    verified        boolean      NOT NULL DEFAULT false,
    blocked         boolean      NOT NULL DEFAULT false,
    provider        varchar(20)  NOT NULL DEFAULT 'LOCAL',
    google_subject  varchar(255),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_users_role CHECK (role IN ('STUDENT', 'TUTOR', 'ADMIN', 'SUPER_ADMIN')),
    CONSTRAINT chk_users_provider CHECK (provider IN ('LOCAL', 'GOOGLE'))
);

CREATE INDEX idx_users_role ON users(role);
