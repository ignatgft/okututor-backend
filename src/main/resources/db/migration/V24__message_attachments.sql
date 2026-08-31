-- Вложения сообщений (messenger + support-чат).
-- Бинарные данные живут в object storage; media_objects хранит метаданные
-- загруженного файла, message_attachments добавляет контекст сообщения
-- (исходное имя файла, классификацию IMAGE/FILE и миниатюру для картинок).
-- Сообщение может ссылаться максимум на одно приложение (attachment_id).

CREATE TABLE message_attachments (
    id                 uuid PRIMARY KEY,
    media_id           uuid NOT NULL REFERENCES media_objects(id) ON DELETE CASCADE,
    thumbnail_media_id uuid REFERENCES media_objects(id) ON DELETE CASCADE,
    original_filename  varchar(255) NOT NULL,
    content_type       varchar(100) NOT NULL,
    size_bytes         bigint NOT NULL,
    kind               varchar(10) NOT NULL DEFAULT 'FILE',
    claimed_at         timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_attachment_kind CHECK (kind IN ('IMAGE', 'FILE'))
);

CREATE INDEX idx_message_attachments_media ON message_attachments(media_id);

-- messenger: сообщение может нести одно вложение (текст остаётся опциональным)
ALTER TABLE messages
    ADD COLUMN attachment_id uuid REFERENCES message_attachments(id) ON DELETE SET NULL;

CREATE INDEX idx_messages_attachment ON messages(attachment_id);

-- support-чат: аналог. ON DELETE SET NULL сохраняет тред при удалении вложения.
ALTER TABLE support_ticket_messages
    ADD COLUMN attachment_id uuid REFERENCES message_attachments(id) ON DELETE SET NULL;

CREATE INDEX idx_ticket_messages_attachment ON support_ticket_messages(attachment_id);