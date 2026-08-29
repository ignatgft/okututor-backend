-- Личные сообщения и уведомления пользователя.
-- SUPPORT/SYSTEM-переписки представлены support_tickets;
-- фронт объединяет их на клиенте (messages.api.js loadUnifiedConversations).

CREATE TABLE conversations (
    id              uuid PRIMARY KEY,
    type            varchar(10) NOT NULL DEFAULT 'DIRECT',
    user1_id        uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user2_id        uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    last_message    text,
    last_message_at timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_conversation_type CHECK (type IN ('DIRECT', 'SUPPORT', 'SYSTEM'))
);

CREATE UNIQUE INDEX uq_direct_pair ON conversations(user1_id, user2_id) WHERE type = 'DIRECT';

CREATE TABLE messages (
    id              uuid PRIMARY KEY,
    conversation_id uuid NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    sender_id       uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    body            text NOT NULL,
    read_at         timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_messages_conversation ON messages(conversation_id, created_at);

CREATE TABLE notifications (
    id          uuid PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    message     text NOT NULL,
    type        varchar(30) NOT NULL DEFAULT 'SYSTEM',
    link        varchar(255),
    read        boolean NOT NULL DEFAULT false,
    read_at     timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_user ON notifications(user_id, created_at DESC);
