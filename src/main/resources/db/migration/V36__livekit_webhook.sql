-- LiveKit webhook: учёт участников встречи и дедупликация повторных событий.
-- Факт окончания встречи (ended_at) уже хранится в meeting_sessions.

ALTER TABLE meeting_sessions
    ADD COLUMN webhook_event_at timestamptz;

CREATE TABLE meeting_participants (
    id                  uuid PRIMARY KEY,
    meeting_session_id  uuid NOT NULL REFERENCES meeting_sessions(id) ON DELETE CASCADE,
    identity            varchar(64) NOT NULL,
    joined_at           timestamptz NOT NULL DEFAULT now(),
    left_at             timestamptz,
    UNIQUE (meeting_session_id, identity)
);

CREATE INDEX idx_meeting_participants_identity ON meeting_participants(identity);

-- защита от повторной доставки одного и того же вебхука (LiveKit at-least-once)
CREATE TABLE livekit_webhook_events (
    event_hash   varchar(64) PRIMARY KEY,
    room_name    varchar(120),
    event        varchar(64),
    received_at  timestamptz NOT NULL DEFAULT now()
);
