-- Тикеты поддержки и сообщения треда.
-- Публичные id человекочитаемые TK-<number>; внутренние PK — UUID.
-- Название статуса ожидания следует за фронтом: WAITING_FOR_USER.

CREATE SEQUENCE IF NOT EXISTS support_ticket_number_seq START 1001;

CREATE TABLE support_tickets (
    id                   uuid PRIMARY KEY,
    number               bigint NOT NULL UNIQUE,
    user_id              uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category             varchar(20) NOT NULL,
    subject              varchar(255) NOT NULL,
    description          text NOT NULL,
    priority             varchar(10) NOT NULL DEFAULT 'NORMAL',
    status               varchar(20) NOT NULL DEFAULT 'OPEN',
    assigned_admin_id    uuid REFERENCES users(id) ON DELETE SET NULL,
    last_message_preview text,
    user_unread_count    int NOT NULL DEFAULT 0,
    admin_unread_count   int NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_ticket_category CHECK (category IN ('TECHNICAL', 'PAYMENT', 'COURSE', 'ACCOUNT', 'BUG')),
    CONSTRAINT chk_ticket_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    CONSTRAINT chk_ticket_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'WAITING_FOR_USER', 'RESOLVED', 'CLOSED'))
);

CREATE INDEX idx_tickets_user ON support_tickets(user_id);
CREATE INDEX idx_tickets_status ON support_tickets(status);

CREATE TABLE support_ticket_messages (
    id          uuid PRIMARY KEY,
    ticket_id   uuid NOT NULL REFERENCES support_tickets(id) ON DELETE CASCADE,
    sender_id   uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sender_role varchar(20) NOT NULL,
    body        text NOT NULL,
    type        varchar(20) NOT NULL DEFAULT 'USER_VISIBLE',
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_ticket_messages_ticket ON support_ticket_messages(ticket_id, created_at);
