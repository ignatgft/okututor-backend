# Приложение → Расписание → Занятия

Домен согласования расписания по подтверждённой заявке и материализации конкретных
занятий (миграции V25–V29). API-контракты — в `mapping.md` (#36b–#36m).

## Модель

```
Enrollment (заявка)  1 ─ 1   Schedule (регулярное расписание)  1 ─ N   ScheduleSlot
     │                        │ status: DRAFT → PROPOSED → CONFIRMED → CANCELLED/COMPLETED
     │                        └─ materialization: CONFIRMED + [start_date,end_date] + slots
     │                                             │
     │                                  1 ─ N  Booking (конкретная встреча)
     │                                            │ 1:1 Lesson (связанный урок)
     │
     └─ 1 ─ N  ScheduleProposal (история согласования, status: PENDING/ACCEPTED/REJECTED/SUPERSEDED/CANCELLED)
               └─ 1 ─ N  ScheduleProposalSlot
```

- **Заявка = Enrollment** (старая таблица, расширена). Статусы:
  `PENDING → ACCEPTED → SCHEDULE_PENDING → SCHEDULE_PROPOSED → SCHEDULED`,
  ответвления `NEEDS_INFO`, терминальные `REJECTED/CANCELLED/EXPIRED`.
  Все переходы — только через `Enrollment.transitionTo` (нелегальные → 409
  `INVALID_APPLICATION_STATE`), см. `ACTIVE_STATUSES` (на них — unique-индекс курса и студента).
- **Preferences заявки** (`preferred_days/start_time/end_time/format/...`) — это ПОЖЕЛАНИЯ,
  а не расписание. Реальные встречи порождаются только из подтверждённого `Schedule`.
- **ScheduleProposal** — отдельная сущность-история: тьютор предлагает, студент
  принимает/отклоняет или делает контрпредложение. При новом PENDING-предложении старые
  PENDING переводятся в `SUPERSEDED` (полная история сохраняется для таймлайна).
- Материализация: `Schedule.accept` атомарно (одна транзакция) → Booking(CONFIRMED) +
  связанные Lessons(SCHEDULED) на каждый слот × даты `[start_date,end_date]` (по
  времени тьютора в `schedule.timezone`). Конфликтные даты пропускаются, результат —
  `{created_count, conflicted_dates, booking_ids}`.

## Поток

1. Тьютор: `POST /schedule/applications/{id}/propose` (заявка `ACCEPTED`/`SCHEDULE_PENDING`).
   Проверки: нет ли уже `PENDING`-предложения (409 `SCHEDULE_NOT_AVAILABLE`), диапазон дат,
   слоты ≥ `duration_minutes`, `weekday` `MONDAY..SUNDAY` (или 1..7). Заявка → `SCHEDULE_PROPOSED`.
2. Студент видит предложения: `GET /schedule/applications/{id}/proposals`.
3. Студент: `POST /schedule/proposals/{id}/accept` → заявка `SCHEDULED`, расписание
   `CONFIRMED`, занятия материализованы. Повторный accept идемпотентен (возвращает текущие
   занятия, `created_count=0`).
4. Студент: `POST .../reject` → заявка `SCHEDULE_PENDING`, предложение `REJECTED` (тьютор
   может предложить заново). `POST .../counter` → новое `PENDING`-предложение, старое —
   `SUPERSEDED`.

## Конфликты и доступность

- `availableSlots` показывает пересечение предпочтений студента и `availability_slots`
  тьютора (weekday-капитализация `Monday`..`Sunday`) на диапазоне дат, минус занятость
  (Booking `PENDING/CONFIRMED/RESCHEDULED` + Lesson `SCHEDULED/IN_PROGRESS`) участников.
- При материализации проверяется покрытие слотом доступности тьютора; недоступные/прошедшие/
  конфликтные даты попадают в `conflicted_dates`.
- `availability_slots.timezone` (IANA, default UTC, миграция V30) задаёт зону локальных
  `start_time/end_time`; `GET /availability/common-slots?tutor_id&student_id&date` возвращает
  пересечение доступности двух пользователей на дату в UTC.
- Офлайн-формат: расписание/урок несут `location_type` (TUTOR_PLACE/STUDENT_PLACE/CENTER/OTHER)
  + `location_address` + `location_details` (миграция V31); для `OFFLINE` обязателен
  `location_type`. Занятия наследуют локацию из подтверждённого `Schedule`.
- UNIQUE-индексы `uq_availability_unique`/`uq_booking_active_unique` защищают от гонки на
  commit; `existsReminder` (native, `payload->>'window'`) дедупит напоминания `24h`/`15m`.

## Жизненный цикл занятий (Lesson)

`SCHEDULED → IN_PROGRESS → COMPLETED`, `CANCELLED` из любого живого. `start/complete/cancel`
зеркалят статус связанного `Booking` (отзыв студента привязан к booking). `reschedule`
(новый `{start_at,end_at}` в UTC, длительность из `{30,45,60,90,120}`) перепроверяет
занятость участников → 409 `LESSON_CONFLICT`. Каждое действие — уведомление (`LESSON_STARTED/
COMPLETED/CANCELLED/RESCHEDULED`) и запись в `audit_logs`, таймлайн доступен через
`GET /applications/{id}/timeline`.

## Аудит и уведомления

- `audit_logs` хранит `old_value/new_value/action` синхронно с переходом статуса (`logSync`).
- `notifications` привязаны к сущности: `entity_type` (`APPLICATION`/`LESSON`) + `entity_id`;
  напоминания — `type=LESSON_REMINDER` + `payload.window`, дедуп по `existsReminder`.