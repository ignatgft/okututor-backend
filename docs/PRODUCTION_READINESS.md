# Okututor Backend — Production Readiness

Дата аудита: 2026-08-26. Версия схемы: V16. Статусы: **DONE** / **PARTIAL** / **BLOCKED** / **TODO**.

## Auth & Security

| Область | Статус | Комментарий |
|---|---|---|
| Регистрация + email verification (код/TTL/resend/rate-limit/brute-force) | DONE | коды хэшируются, attempts в REQUIRES_NEW-транзакции, лимиты verify/resend |
| Login (пароль/blocked/unverified/wrong password) | DONE | единые английские сообщения, анти-перебор |
| JWT access/refresh: rotation, reuse detection, grace-окно, revoke | DONE | `RefreshTokenService` + session metadata |
| Refresh token как httpOnly Secure cookie | TODO | сейчас токены в JSON-body; для MVP допустимо при XSS-гигиене фронта |
| Google OAuth → только STUDENT, эскалация роли запрещена | DONE | placeholder-guard в SecurityConfig; TUTOR через заявку |
| RBAC STUDENT/TUTOR/ADMIN/SUPER_ADMIN на backend | DONE | @PreAuthorize + явные guard'ы; identity из principal |
| IDOR (bookings/courses/users) | DONE | requireParticipantView / requireOwnerOrAdmin / isAuthor |
| Rate limiting (Redis опционально) | DONE | Redis или локальный sliding-window per-key |
| CORS / FRONTEND_URL через ENV | DONE | prod-профиль без dev-дефолтов |
| Secrets: без значений в Git, .env.example, ProdEnvValidator | DONE | история проверена (`git log -S`); Resend-ключ не утекал |
| Actuator: наружу health (+info в dev) | DONE | prod: только health |

## Domain

| Область | Статус | Комментарий |
|---|---|---|
| Courses CRUD + модерация approve/reject/hide | DONE | статусная модель DRAFT→PENDING→APPROVED/REJECTED |
| Search в PostgreSQL (q/subject/location/group/maxPrice/**priceMin**/**ratingMin**) | DONE | join fetch teacher (без N+1), индексы V16 |
| Сортировка search по параметру (`sort=rating,desc` и т.п.) | TODO | сейчас фиксированный порядок createdAt desc (контракт фронта не требует sort); FTS-ранжирование есть в `/search/courses` |
| Фильтры language/duration/tutor | TODO | полей нет в модели — потребуется расширение схемы |
| Booking flow + state machine в entity | DONE | PENDING→CONFIRMED→COMPLETED/CANCELLED/REJECTED |
| Double booking protection | DONE | частичные уникальные индексы (teacher/student,start_at) + `BookingConcurrencyIT`: 100 параллельных → 1 success |
| Запрет брони в прошлом / своей услуги | DONE | `start.isBefore(now)` + teacher==student guard |
| Availability weekly slots, UTC | PARTIAL | недельные слоты в UTC; blocked periods (отпуска) — TODO |
| LiveKit: participant-only + статус + временное окно | DONE | CONFIRMED/COMPLETED, окно start−15м..end+60м (`app.lesson.*`); secret не покидает backend |
| Reviews: 1 на студента-курс, hidden вне агрегата, атомарный пересчёт | DONE | unique constraint + bulk UPDATE |
| Notifications async (booking/support/message/system) | DONE | ThreadPoolTaskExecutor 4/16/500 CallerRuns |
| Email через events | PARTIAL | отправка асинхронная, но через прямые вызовы Notification/Mail, не Spring Events |

## Infrastructure

| Область | Статус | Комментарий |
|---|---|---|
| Flyway миграции V1–V16, без ручных правок схемы | DONE | validate-on-migrate=false только из-за исторических dev-БД |
| N+1 аудит списков | DONE | join fetch: bookings/enrollments/reviews/lessons/conversations/courses |
| Bean Validation DTO | DONE | auth/courses/support/tutor-applications; Map-payloads устранены |
| Единый error format + traceId, без stacktrace | DONE | GlobalExceptionHandler |
| Graceful shutdown | DONE | server.shutdown=graceful, timeout 30s |
| Docker multi-stage, non-root, healthcheck, без secrets в layers | DONE | JDK JRE-21 образ, user okututor |
| docker-compose через ENV | DONE | дефолты только dev |
| Production config validation at startup | DONE | ProdEnvValidator (EnvironmentPostProcessor): DB_*/JWT_SECRET(≥32)/CORS/FRONTEND/MAIL_FROM |
| Seed: env-пароли, выключен в prod-профиле жёстко | DONE | |
| CI GitHub Actions (mvn verify + Testcontainers, compose validate) | DONE | `.github/workflows/ci.yml`; deploy-step — TODO |
| OpenAPI/Swagger | DONE | springdoc автогенерация по актуальным DTO |
| Observability: metrics (upload/compression/rate), health | PARTIAL | медиа-метрики есть; HTTP-latency/error-rate — из коробки Micrometer, дашбордов нет |
| Frontend реально работает с API | BLOCKED here | проверяется интеграцией репозиториев; контракт синхронизирован через docs/mapping.md |

## Известные риски

1. **Refresh token в JSON-body** — до внедрения httpOnly cookies остаётся XSS-зависимым на стороне фронта.
2. **validate-on-migrate=false** — легаси-dev-БД могут расходиться со схемой; перед продом поднять чистую БД и прогнать миграции последовательно.
3. **Search sort** не параметризован — при появлении требования сортировки в UI добавить Specification-based поиск.
4. **Availability blocked-periods** отсутствуют — tutor не может заблокировать отпуск.
5. **Единый тестовый Google OAuth client** для dev/staging — перед продом создать отдельный client.

## Ближайшие шаги

1. httpOnly Secure SameSite cookie для refresh token.
2. Blocked periods availability + timezone-документация API.
3. Sort/search расширение (Specification), language/duration поля.
4. Deploy-stage в CI после инфраструктуры.
