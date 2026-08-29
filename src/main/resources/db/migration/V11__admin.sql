-- Админский домен: неизменяемый аудит-след и жалобы.
-- У жалоб пока нет пользовательского эндпоинта создания (пробел во фронте); наполняется seed-ом.

CREATE TABLE audit_logs (
    id          uuid PRIMARY KEY,
    actor_id    uuid REFERENCES users(id) ON DELETE SET NULL,
    action      varchar(100) NOT NULL,
    target_type varchar(50),
    target_id   varchar(64),
    details     text,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_actor ON audit_logs(actor_id, created_at DESC);
CREATE INDEX idx_audit_action ON audit_logs(action);

CREATE TABLE reports (
    id          uuid PRIMARY KEY,
    reporter_id uuid REFERENCES users(id) ON DELETE SET NULL,
    target_type varchar(20) NOT NULL,
    target_id   varchar(64) NOT NULL,
    reason      text NOT NULL,
    status      varchar(20) NOT NULL DEFAULT 'OPEN',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_report_status CHECK (status IN ('OPEN', 'IN_REVIEW', 'RESOLVED', 'DISMISSED'))
);
