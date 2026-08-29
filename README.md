# Okututor Backend

Spring Boot 3 (Java 21) API для фронта [`front_okututor`](https://github.com/ignatgft/front_okututor).
Контракт — `docs/mapping.md` (источник правды: `src/api/endpoints.js` фронта).

## Стек

Java 21 · Spring Boot 3.5 (Web, Security, Data JPA, Mail, OAuth2 Client, Actuator) · PostgreSQL 16 + Flyway ·
JWT (jjwt) · LiveKit access tokens · springdoc OpenAPI · Testcontainers · Docker Compose.

## Быстрый старт (Docker Compose)

```bash
docker compose up --build
# backend:      http://localhost:8080/api/v1/...
# swagger:      http://localhost:8080/swagger-ui.html
# mailpit UI:   http://localhost:8025  (письма с кодами верификации)
```

Фронт собирается с `VITE_API_URL=/api` и ходит через свой nginx (`proxy_pass http://backend:8080/api/`).

## Локальная разработка без Docker

```bash
cp .env.example .env   # заполнить значения (шаблон содержит подсказки по каждому ключу)
docker compose up -d postgres redis mailpit
mvn spring-boot:run
```

При `APP_MAIL_ENABLED=false` коды писем пишутся в лог приложения.

## Ключевые переменные окружения

Полный справочник с обязательностью по окружениям: **[docs/environment.md](docs/environment.md)**.
Шаблон: `.env.example` (в Git), реальные значения — только в локальном `.env` / серверном окружении.

| Переменная | Назначение | По умолчанию |
|---|---|---|
| `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD` | PostgreSQL | localhost/5432/okututor |
| `JWT_SECRET` | секрет подписи JWT (**обязателен** вне dev; в prod без него старт невозможен) | dev-only value |
| `LIVEKIT_WS_URL/LIVEKIT_API_KEY/LIVEKIT_API_SECRET` | LiveKit сервер | dev-заглушки |
| `GOOGLE_CLIENT_ID/GOOGLE_CLIENT_SECRET` | Google OAuth | пусто = отключён |
| `FRONTEND_URL` | redirect после OAuth | http://localhost:5173 |
| `APP_CORS_ORIGINS` | whitelist CORS | localhost:5173,3000 |
| `APP_MAIL_ENABLED` | реально отправлять письма | false (коды в лог) |
| `APP_SEED_ENABLED` | идемпотентный seed данных | true (в prod-профиле выключен жёстко) |

## Production

1. ENV задаётся на сервере (systemd/secrets manager); `.env` не коммитится.
2. Профиль: `SPRING_PROFILES_ACTIVE=prod`.
3. Обязательны: `DB_*`, `JWT_SECRET` (≥32 случайных символов), `FRONTEND_URL`, `APP_CORS_ORIGINS`, `MAIL_FROM`, SMTP-креды.
4. Seed в prod-профиле выключен на уровне конфигурации; пароли демо-аккаунтов не используются.
5. Наружу открыт только `/actuator/health`; остальное закрывается reverse-proxy.
6. Валидатор `ProdEnvValidator` не даст стартовать с отсутствующими/слабыми секретами.

## Тесты

```bash
mvn test        # юнит-тесты (без Docker)
mvn verify      # + интеграционные (Testcontainers; требуют запущенный Docker)
```

Интеграционный `BookingConcurrencyIT` проверяет защиту от double-booking:
100 параллельных броней одного слота → ровно 1 success, 99 × 409 CONFLICT.

## Seed-данные (dev/staging)

При `APP_SEED_ENABLED=true` создаются (идемпотентно):

| Аккаунт | Пароль | Роль |
|---|---|---|
| `super@admin.test` | `Admin#12345` | SUPER_ADMIN |
| `support@admin.test` | `Admin#12345` | ADMIN |
| `tutor@test.com` | `Tutor#12345` | TUTOR |
| `test@test.com` | `Student#12345` | STUDENT |

Плюс 3 одобренных курса, слоты доступности, брони, support-тикет и уведомления.
Пароли задаются переменными `SEED_ADMIN_PASSWORD`, `SEED_SUPPORT_PASSWORD`,
`SEED_TUTOR_PASSWORD`, `SEED_STUDENT_PASSWORD` (значения ниже — только dev-дефолты).
В профиле `prod` seed выключен на уровне конфигурации.
