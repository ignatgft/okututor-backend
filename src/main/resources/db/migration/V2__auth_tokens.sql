-- Refresh-токены (ротация при использовании + детект переиспользования family) и одноразовые email-коды.

CREATE TABLE refresh_tokens (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  varchar(64) NOT NULL UNIQUE,
    family_id   uuid NOT NULL,
    expires_at  timestamptz NOT NULL,
    rotated_at  timestamptz,
    revoked_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens(family_id);

CREATE TABLE email_codes (
    id           uuid PRIMARY KEY,
    email        varchar(255) NOT NULL,
    user_id      uuid REFERENCES users(id) ON DELETE CASCADE,
    new_email    varchar(255),
    purpose      varchar(30) NOT NULL,
    code_hash    varchar(64) NOT NULL,
    expires_at   timestamptz NOT NULL,
    attempts     int NOT NULL DEFAULT 0,
    consumed_at  timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_email_codes_purpose CHECK (purpose IN ('EMAIL_VERIFY', 'PASSWORD_RESET', 'EMAIL_CHANGE'))
);

CREATE INDEX idx_email_codes_email_purpose ON email_codes(email, purpose);
