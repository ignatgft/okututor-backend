# Environment Variables — Okututor Backend

Все секреты приходят только через окружение (`.env` локально, secrets manager / systemd env на сервере).
Шаблон: [`.env.example`](../.env.example). Файл `.env` в Git не попадает.

Приоритет источников: **OS environment > .env > application-{profile}.yml > application.yml**.

Профили:

| Профиль | Назначение | Особенности |
|---------|-----------|-------------|
| `dev` (дефолт) | локальная разработка | Mailpit/Resend sandbox, seed включён, DEBUG-лог |
| `prod` | production/стейджинг | строгая валидация ENV при старте (`ProdEnvValidator`), seed жёстко выключен, наружу только `/actuator/health` |

## Обязательные

| Variable | Назначение | DEV | PROD |
|----------|-----------|-----|------|
| `DB_HOST` / `DB_PORT` | адрес PostgreSQL | localhost:5432 | YES |
| `DB_NAME` / `DB_USER` | БД и пользователь | okututor | YES |
| `DB_PASSWORD` | пароль PostgreSQL | dev-значение | YES, случайный |
| `JWT_SECRET` | подпись access/refresh токенов | есть dev-дефолт | YES, ≥32 случайных символа; без него старт невозможен; dev-значения запрещены |
| `FRONTEND_URL` | URL фронта (OAuth redirect, письма) | http://localhost:5173 | YES, https-домен |
| `APP_CORS_ORIGINS` | разрешённые origins через запятую | localhost:5173 | YES, только прод-домены |
| `MAIL_FROM` | отправитель писем | noreply@goidaai.ru | YES, доменный |

## Интеграции

| Variable | Назначение | DEV | PROD |
|----------|-----------|-----|------|
| `APP_MAIL_ENABLED` | false = письма в лог ([DEV MAIL]), true = SMTP | true/false | true |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USER` | SMTP-эндпоинт | smtp.resend.com / mailpit | YES |
| `RESEND_API_KEY` | ключ SMTP Resend (вместо пароля) | опционально | YES |
| `MAIL_SMTP_AUTH` / `MAIL_SMTP_STARTTLS` | параметры SMTP | true | true |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Google OAuth2; пусто = вход отключён | optional | YES (отдельные креды для стейджинга) |
| `LIVEKIT_WS_URL` / `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` | видеоуроки; секрет остаётся в backend, фронт получает только временные токены | optional | YES (если уроки включены) |
| `SPRING_DATA_REDIS_HOST` / `_PORT` / `REDIS_PASSWORD` | rate-limit хранилище | localhost без пароля | YES с паролем |

## Seed (демо-данные)

| Variable | Назначение | DEV | PROD |
|----------|-----------|-----|------|
| `APP_SEED_ENABLED` | создание демо-аккаунтов/курсов | true | игнорируется — в prod-профиле всегда false |
| `SEED_ADMIN_PASSWORD` | пароль super@admin.test | Admin#12345 | не используется |
| `SEED_SUPPORT_PASSWORD` | пароль support@admin.test | Support#12345 | не используется |
| `SEED_TUTOR_PASSWORD` | пароль tutor@test.com | Tutor#12345 | не используется |
| `SEED_STUDENT_PASSWORD` | пароль test@test.com | Student#12345 | не используется |

## Media storage

| Variable | Назначение | DEV | PROD |
|----------|-----------|-----|------|
| `STORAGE_PROVIDER` | `local` или `r2` | local | r2 + CDN |
| `MEDIA_IMAGE_FORMAT`, `MEDIA_*_MAX_WIDTH/HEIGHT`, `MEDIA_*_QUALITY` | параметры оптимизации изображений | дефолты спеки | по нагрузке |
| `R2_ACCOUNT_ID` / `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` / `R2_BUCKET` / `R2_PUBLIC_BASE_URL` | Cloudflare R2 | optional | YES при provider=r2 |

## Прочее

| Variable | Назначение | Значение по умолчанию |
|----------|-----------|----------------------|
| `SERVER_PORT` | порт HTTP | 8080 |
| `SPRING_MULTIPART_MAX_FILE_SIZE` | лимит загрузки файлов | 11MB |
| `MEDIA_ORPHAN_CLEANUP_ENABLED` / `MEDIA_ORPHAN_GRACE_HOURS` | очистка осиротевших медиа-объектов | false / 24 |
| `ACTUATOR_EXPOSURE` | подсказка для reverse-proxy ACL | health |

## Правила безопасности

1. Секреты **никогда** не коммитятся: `.env*` в `.gitignore`, кроме `.env.example`.
2. `application-prod.yml` не содержит значений — только плейсхолдеры `${VAR}`.
3. При старте с профилем `prod` валидатор проверяет обязательные переменные и силу `JWT_SECRET`; иначе JVM завершается до подъёма бинов.
4. Секреты не логируются; коды подтверждения пишутся в лог только dev-заглушкой при `APP_MAIL_ENABLED=false`.
5. Если секрет попал в Git — считать скомпрометированным: rotate немедленно, затем чистить историю (git filter-repo/BFG).
