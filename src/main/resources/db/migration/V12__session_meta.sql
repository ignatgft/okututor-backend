-- Метаданные пользовательских сессий поверх refresh-токенов:
-- таблица refresh_tokens фактически и является user_sessions.

ALTER TABLE refresh_tokens ADD COLUMN device     varchar(64);
ALTER TABLE refresh_tokens ADD COLUMN user_agent text;
ALTER TABLE refresh_tokens ADD COLUMN ip         varchar(45);
