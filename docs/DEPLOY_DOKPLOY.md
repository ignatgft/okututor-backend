# Развертывание Okututor Backend в Dokploy

Backend: Spring Boot 3.5 / Java 21, PostgreSQL 16 (FTS + pg_trgm), Redis 7,
SMTP (Resend или любой другой). В репозитории уже есть multi-stage `Dockerfile`
и `docker-compose.yml`. Миграции Flyway применяются автоматически при старте.

---

## Архитектура деплоя

```
Dokploy-сервер
├── Application: okututor-backend   (Dockerfile, порт 8080, домен api.*)
├── Database:    PostgreSQL 16      (managed Dokploy)
├── Database:    Redis 7            (managed Dokploy)
└── Volume:      /app/data          (загрузки: аватары, обложки курсов)
```

Что нужно приложению:

| Зависимость | Зачем | Обязательна |
|---|---|---|
| PostgreSQL 16 | данные + поиск (FTS, pg_trgm) | да |
| Redis 7 | rate-limit, сессии | да |
| SMTP / Resend | письма (верификация, уведомления) | да |
| LiveKit | видео-уроки | нет (если не используется) |
| Google OAuth | вход через Google | нет |

---

## Вариант A (рекомендуется): Application + managed БД

### 1. Создать PostgreSQL

Dokploy → **Databases** → **Create Database** → **PostgreSQL 16**.

После создания на вкладке **Connection** взять внутренние реквизиты:
`Internal Connection String` вида
`postgresql://<user>:<password>@<service-name>:5432/<db>`.

> Пользователь managed-Postgres в Dokploy имеет права на `CREATE EXTENSION` —
> Flyway сам выполнит `CREATE EXTENSION IF NOT EXISTS pg_trgm` (V13/V17).

### 2. Создать Redis

Dokploy → **Databases** → **Create Database** → **Redis 7**.
Записать внутренний хост (имя сервиса) и пароль (если задан).

### 3. Создать приложение

Dokploy → **Projects** → создать проект → **Create Application**:

1. **Source**: GitHub → репозиторий `ignatgft/okututor-backend`, ветка `main`.
2. **Build Type**: `Dockerfile` (путь `Dockerfile` в корне репозитория).
3. **Port**: `8080`.

### 4. Переменные окружения

Вкладка **Environment** приложения. Минимально необходимый набор:

```bash
# --- PostgreSQL (из шага 1) ---
DB_HOST=<service-name-postgres>
DB_PORT=5432
DB_NAME=okututor
DB_USER=okututor
DB_PASSWORD=<сильный пароль>

# --- Redis (из шага 2) ---
SPRING_DATA_REDIS_HOST=<service-name-redis>
SPRING_DATA_REDIS_PORT=6379
REDIS_PASSWORD=<пароль redis, если задан>
APP_RATE_LIMIT_USE_REDIS=true

# --- Безопасность ---
JWT_SECRET=<64+ случайных символов: openssl rand -hex 32>

# --- Frontend / CORS ---
FRONTEND_URL=https://app.example.com
APP_CORS_ORIGINS=https://app.example.com

# --- Продакшен-режимы ---
APP_SEED_ENABLED=false          # ВАЖНО: не сидить демо-данные в prod
APP_MAIL_ENABLED=true

# --- Почта: Resend (рекомендуется) ---
RESEND_API_KEY=re_xxxxxxxxxxxx
MAIL_FROM=Okututor <no-reply@example.com>

# --- Хранилище загрузок (volume /app/data) ---
STORAGE_PROVIDER=local
APP_STORAGE_DIR=/app/data/uploads
APP_STORAGE_PUBLIC_BASE=/api/v1/files
```

Почта через произвольный SMTP вместо Resend:

```bash
MAIL_HOST=smtp.example.com
MAIL_PORT=587
MAIL_USER=user@example.com
MAIL_PASSWORD=***
MAIL_SMTP_AUTH=true
MAIL_SMTP_STARTTLS=true
```

Опциональные (только если используются фичи):

```bash
# Google OAuth
GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=***
GOOGLE_REDIRECT_URI=https://api.example.com/api/v1/auth/oauth2/callback/google

# LiveKit (видео-уроки)
LIVEKIT_WS_URL=wss://livekit.example.com
LIVEKIT_API_KEY=***
LIVEKIT_API_SECRET=***

# AI-парсер поиска (по умолчанию выключен; поиск автономен без него)
OKUTUTOR_SEARCH_AI_ENABLED=false
```

### 5. Volume для загрузок

Вкладка **Volumes** → **Add Volume**:

- Mount path: `/app/data`
- Тип: persistent volume

Без volume аватары/обложки теряются при редеплое.

### 6. Домен и HTTPS

Вкладка **Domains** → **Add Domain**:

- Host: `api.example.com`
- Port: `8080`
- HTTPS: включить (Dokploy выпустит Let's Encrypt автоматически)

### 7. Healthcheck и ресурсы

- Healthcheck уже в `Dockerfile`: `GET /actuator/health` (каждые 15 c).
- В настройках контейнера задать лимит памяти ≥ 768 МБ
  (JVM стартует с `-XX:MaxRAMPercentage=75`).

### 8. Деплой

**Deploy**. При первом старте Flyway накатит все миграции (V1–V18),
включая FTS-векторы и индексы поиска. Автодеплой: каждый push в `main`
пересобирает и перезапускает приложение (настройка в **Advanced**).

---

## Вариант B: Docker Compose

Если хочется развернуть весь стек одним compose-файлом (включая Mailpit
для перехвата писем):

Dokploy → **Projects** → **Create Application** → тип **Docker Compose** →
репозиторий `ignatgft/okututor-backend`, compose-путь `docker-compose.yml`.

Задать переменные окружения (подхватываются compose):

```bash
DB_PASSWORD=***
JWT_SECRET=***
MAIL_HOST=smtp.example.com      # или оставить mailpit для staging
MAIL_PORT=587
MAIL_USER=...
MAIL_PASSWORD=***
MAIL_SMTP_AUTH=true
MAIL_SMTP_STARTTLS=true
APP_MAIL_ENABLED=true
APP_SEED_ENABLED=false
APP_CORS_ORIGINS=https://app.example.com
FRONTEND_URL=https://app.example.com
```

Домен повесить на сервис `backend`, порт `8080`.

> Имя сервиса `backend` в compose совпадает с `proxy_pass` целью nginx
> во frontend-репозитории — не переименовывать.

Минусы варианта B: БД живёт в проекте, а не в managed-сервисах Dokploy
(бэкапы/мониторинг — вручную). Для продакшена рекомендуется вариант A.

---

## Проверка после деплоя

```bash
# health
curl https://api.example.com/actuator/health
# {"status":"UP"}

# поиск (крест-языковой)
curl "https://api.example.com/api/v1/search/courses?q=пайтон"
curl "https://api.example.com/api/v1/search/courses/v2?q=математика"

# каталог
curl "https://api.example.com/api/v1/courses?page=0&size=5"
```

В логах Dokploy убедиться:

- `Flyway ... Successfully validated N migrations` / `Migrating schema` — при первом старте;
- нет ошибок подключения к PostgreSQL/Redis;
- `Started OkututorBackendApplication`.

---

## Обновление и откат

- **Обновление**: push в `main` → автодеплой, либо кнопка **Redeploy**.
  Flyway накатывает новые миграции автоматически; простой — только на
  время перезапуска контейнера (healthcheck держит старый контейнер до
  готовности нового, если включен zero-downtime в настройках Dokploy).
- **Откат**: Dokploy → **Deployments** → выбрать предыдущий деплой → **Rollback**.
  Внимание: миграции Flyway не откатываются автоматически — при откате
  на версию до последней миграции может понадобиться ручная правка схемы.

## Бэкапы

- PostgreSQL: Dokploy → база → вкладка **Backups** (настроить расписание
  и S3-хранилище).
- Volume `/app/data`: бэкапить отдельно (S3/rsync), если загрузки важны.

---

## Частые проблемы

| Симптом | Причина / решение |
|---|---|
| `extension "pg_trgm" is not available` / нет прав | DB-пользователь без прав суперпользователя. В managed-Postgres Dokploy прав достаточно; на внешней БД — `CREATE EXTENSION pg_trgm` вручную суперпользователем |
| Приложение падает по OOM | Поднять лимит памяти контейнера (≥ 768 МБ, лучше 1 ГБ) |
| CORS-ошибки у фронтенда | `APP_CORS_ORIGINS` не содержит домен фронтенда (список через запятую) |
| Письма не уходят | Проверить `APP_MAIL_ENABLED=true` и `RESEND_API_KEY`/SMTP-реквизиты; в логах искать `MAIL` |
| Загрузки 404 после редеплоя | Не примонтирован volume `/app/data` |
| В prod появились демо-курсы | Забыт `APP_SEED_ENABLED=false` |
| Поиск не находит по опечаткам | Не применился pg_trgm: проверить `\dx` в БД и логи Flyway (V13/V17) |
