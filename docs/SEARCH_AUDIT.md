# SEARCH AUDIT — Okututor Backend

Дата: 2026-08-27. Аудит текущей реализации поиска перед оптимизацией (этапы 1–6).

> **ИТОГ: этапы 1–6 выполнены.** Итоговая архитектура — `SEARCH_ARCHITECTURE.md`,
> API — `SEARCH_API.md`, производительность — `SEARCH_PERFORMANCE.md`.
>
> - **Этап 1:** исправлены критические баги C1–C5, C7 (см. блок ниже).
> - **Этап 2:** V18 (веса FTS, trgm по lower(title), парциальный индекс каталога,
>   дедуп индексов); кандидатский native-запрос с DTO-проекцией (N+1 устранён);
>   `to_tsquery` с префиксами вместо `plainto_tsquery`; RankingService с
>   нормализованными факторами; Bean Validation параметров; каталог с NULLS LAST.
> - **Этап 3:** StructuredQuery + RuleBasedQueryParser (RU/KG/EN словари, класс,
>   цена, формат, цель); optional AI-парсер с fallback на rules; извлечённые
>   фильтры применяются как hard filters.
> - **Этап 4:** availability-фактор (slots − active bookings, батч); объяснения;
>   `GET /api/v1/search/courses/v2` (results + extracted_filters + explanation).
> - **Этап 5:** персонализация — boost по истории бронирований/зачислений
>   (только boost, не фильтр; анонимы не затронуты).
> - **Этап 6:** k6-скрипт, structured timing-логи, EXPLAIN-проверки, документация;
>   локальный замер: p50=9.3ms, p95=18.9ms (цели p50<200/p95<500 превышены).
>
> Ниже — исходный аудит (история проблем; всё из блоков Critical/Performance
> устранено или задокументировано как осознанный trade-off).

> **Статус Этапа 1 (выполнен):** исправлены критические баги C1–C5 и C7 (см. блок ниже):
> SynonymExpander пересобран без возможности дубликатов; стоп-слова и лимит длины
> в нормализаторе; кириллица больше не ломается раскладочной коррекцией; hard-фильтры
> применяются в SQL на `/api/v1/search/courses`; синоним-матчинг переведён с
> неработоспособного `LIKE '%все токены строкой%'` на regex-альтернативу по токенам.
> Проверено живым прогоном (price_max/price_min/subject/rating_min, RU/EN, синонимы, стоп-слова).
> Оставшиеся проблемы (P1–P8, ranking, FTS-конфигурации, KG) — Этапы 2–6.

## Current architecture

Два независимых поисковых пути + заглушки:

| Endpoint | Путь | Реализация |
|---|---|---|
| `GET /api/v1/courses?q&subject&location_type&group_size&max_price&price_min&rating_min&page&size` | `CourseController.list` → `CourseService.search` → JPQL `CourseRepository.search` | `LIKE '%q%'` по title/description; фильтры в SQL работают |
| `GET /api/v1/search/courses` (те же параметры) | `SearchController` → `CourseSearchService.search` → `SearchQueryNormalizer` → native `CourseRepository.searchAdvanced` | FTS + pg_trgm + LIKE; **фильтры игнорируются** |
| `GET /api/v1/search/tutors` | `SearchController.searchTutors` | **заглушка**: всегда `PageResponse.empty()` |
| `GET /api/v1/search/suggestions` | `CourseSearchService.suggestions` | курсы через `searchAdvanced`; **список репетиторов вычисляется и выбрасывается** |

Нормализация запроса (`/api/v1/search/*`):
`SearchQueryNormalizer` → `KeyboardLayoutNormalizer.correctLayout` + `SynonymExpander.expand`
→ `ftsQuery` (`token:* & token:*`), `fuzzyQuery` (`token% | token%`), `expandedTokens`.

Репозиторий: `CourseRepository.searchAdvanced` (native), `searchWithAlternatives` (native, мёртвый код —
вызывается только из `CourseSearchService.searchWithAlternatives`, который не подключён ни к одному endpoint),
`searchFts` (native, мёртвый код), JPQL `search`.

## Current SQL flow

`searchAdvanced` (основной FTS-путь):

```sql
SELECT c.*,
       (CASE WHEN lower(c.title) = lower(:q_exact) THEN 100
             WHEN lower(c.title) LIKE lower(:q_prefix) THEN 80      -- BUG: нет '%'
             WHEN lower(c.title) LIKE lower(:q_contains) THEN 60    -- BUG: нет '%'
             WHEN lower(c.subject) = lower(:q_exact) THEN 50
             WHEN lower(c.category) = lower(:q_exact) THEN 40
             ELSE 0 END) AS exact_score,
       ts_rank_cd(c.search_vector, plainto_tsquery('english', :q_fts)) AS fts_score,
       similarity(lower(c.title), lower(:q_sim)) AS trigram_score
FROM courses c
WHERE c.status = 'APPROVED'
  AND ( :q_fts IS NOT NULL AND c.search_vector @@ plainto_tsquery('english', :q_fts)
     OR :q_fuzzy IS NOT NULL AND c.title % :q_fuzzy
     OR :q_syn IS NOT NULL AND (lower(c.title) LIKE concat('%', lower(:q_syn), '%') OR ...) )
ORDER BY exact_score DESC, fts_score DESC, trigram_score DESC, average_rating DESC NULLS LAST, created_at DESC
```

Проблемы потока:

1. `plainto_tsquery('english', 'java:* & backend:*')` — нормализатор строит запрос в синтаксисе
   `to_tsquery` (`:*`, `&`), но репозиторий прогоняет его через `plainto_tsquery`, который считает
   `:*`/`&` мусором. **Префиксный матчинг теряется**, операторы игнорируются.
2. `c.title % :q_fuzzy` получает строку вида `java% | backend%` (все токены, склеенные через `|`) —
   `similarity()` считается от всей склейки, а не от отдельных токенов. Fuzzy практически не работает.
3. `:q_syn` — все токены + синонимы одной строкой; `LIKE '%...%'` по 4 колонкам (включая `description`)
   с лидирующим `%` — seq scan / trgm-индексы не используются (запрос по `lower(title)`, а GIN trgm
   построен по `title` без `lower()`).
4. `exact_score` почти всегда 0: `q_prefix`/`q_contains` передаются без wildcard-символов,
   т.е. `LIKE` вырождается в точное сравнение; все 4 параметра — один и тот же `originalTokens.get(0)`.

## Current bottlenecks

### Критические баги корректности

| # | Баг | Где | Эффект |
|---|---|---|---|
| C1 | `Set.of(...)` с дубликатами | `SynonymExpander` (original) | `IllegalArgumentException` при инициализации класса → `ExceptionInInitializerError`, поиск `/search/*` падает целиком |
| C2 | `correctLayout()` инвертирует раскладку: чисто кириллический текст транслитерируется RU→EN | `KeyboardLayoutNormalizer.correctLayout` | `"математика"` → `"vfnfvfnbrf"` — **все русские запросы ломаются до БД** |
| C3 | Параметры `subject/locationType/groupSize/maxPrice/priceMin/ratingMin/status` объявлены в сигнатуре, но **не используются в SQL** | `CourseRepository.searchAdvanced`, `searchWithAlternatives` (+countQuery) | Hard filters молча игнорируются на `/api/v1/search/courses`: `max_price=1000` не отсекает курс за 2000 |
| C4 | Результат `removeStopWords()` не используется | `SearchQueryNormalizer.normalize` (строка `withoutStopWords`) | стоп-слова попадают в токены/FTS (падающий тест `removesStopWords`) |
| C5 | `ftsQuery` не ограничен по длине после сборки | `SearchQueryNormalizer.buildFtsQuery` | итоговая строка может превысить MAX_QUERY_LENGTH (падающий тест `maxQueryLength_isEnforced`) |
| C6 | `search_vector_ru` (V17) не используется нигде в коде | `CourseRepository` | русский FTS работает через `english`-конфигурацию: нет стемминга RU («математике» ≠ «математика») |
| C7 | `q_syn` передавал все токены+синонимы **одной строкой** в `LIKE '%...%'` | `CourseSearchService` → `searchAdvanced` | паттерн «математика математике мат mathematics math» не матчится никогда — синонимы фактически не работали (исправлено regex-альтернативой по токенам) |

### Производительность

| # | Проблема | Где | Эффект |
|---|---|---|---|
| P1 | **N+1 по teacher**: native query возвращает `c.*` → `Course` с lazy `teacher`; `CourseResponse.from()` вызывает `teacher.getFullName()` | `CourseSearchService.search/suggestions` | +1 SELECT на строку (20 на страницу) |
| P2 | Загружается полный entity-граф `Course` (включая `description text`) вместо проекции | `searchAdvanced` → `Page<Course>` | лишняя память/трафик |
| P3 | `LIKE '%q%'` с лидирующим wildcard по title+description | JPQL `search` (`/api/v1/courses`) | full scan на каждый запрос каталога с q |
| P4 | `LIKE %:q%` по email/имени репетитора | `UserRepository.searchTutors` | не использует GIN trgm `idx_users_name_trgm` |
| P5 | Дублирующие индексы: `idx_courses_price` (V16) и `idx_courses_price_per_hour` (V17) — одна колонка; 3 индекса на `subject` | V4/V16/V17 | лишний вес/запись |
| P6 | `ORDER BY average_rating DESC` без `NULLS LAST` в каталоге без q | `findByStatusOrderByAverageRatingDescCreatedAtDesc` | в PG `DESC` = `NULLS FIRST` — курсы **без рейтинга показываются первыми** |
| P7 | OFFSET-пагинация без tiebreaker'а `id` | все search-запросы | нестабильный порядок при равных score/rating |
| P8 | countQuery дублирует тяжёлый WHERE с `LIKE`/FTS | `searchAdvanced` | двойная работа на каждый запрос |

### Функциональные

- `/api/v1/search/tutors` — заглушка (пустая страница).
- `suggestions()` считает репетиторов и выбрасывает (`return courses`).
- Нет валидации параметров поиска (длина q, отрицательные цены, page_size, rating_min) на `/search/*`;
  в `/courses` rating_min частично проверяется в сервисе.
- Нет KG-поддержки: кыргызские синонимы/стоп-слова отсутствуют («англис тили», «мугалим», «репетитор»).
- Синонимы: hardcoded `Map.ofEntries`, без нормализации форм слов, без дедуп-защиты, EN-centric.
- Ranking: веса захардкожены в SQL (100/80/60/50/40), не конфигурируются, нет нормализации факторов.
- Нет explanation, extracted_filters, query understanding, персонализации, timing-логов.

## Existing indexes

| Индекс | Источник | Использование |
|---|---|---|
| `courses PK`, `idx_courses_teacher`, `idx_courses_subject`, `idx_courses_status` | V4 | subject/status — частично |
| `idx_courses_search` GIN (`search_vector`) | V13 | да (`searchAdvanced`) |
| `idx_users_name_trgm` GIN trgm | V13 | **нет** (запросы через `LIKE %q%`) |
| `idx_courses_subject_lower` (lower(subject)) | V16 | да (JPQL `lower(c.subject)=lower(:subject)`) |
| `idx_courses_status_rating_created` | V16/V17 | да (каталог без q) |
| `idx_courses_price` / `idx_courses_price_per_hour` | V16 / V17 | дублируют друг друга |
| `idx_courses_title_trgm`, `idx_courses_subject_trgm`, `idx_courses_category_trgm` | V17 | частично: `%` оператор работает, `LIKE lower(...)` — нет |
| `idx_courses_search_ru` GIN (`search_vector_ru`) | V17 | **нет** (не используется в коде) |

## Missing indexes

1. `lower(title)` gin_trgm_ops — под `lower(title) LIKE '%q%'` (сейчас mismatch с `title gin_trgm_ops`).
2. Partial-индекс каталога: `(average_rating DESC NULLS LAST, created_at DESC) WHERE status='APPROVED'`
   — под основной каталог без q (одновременно чинит P6).
3. `(status, price_per_hour)` — под комбинацию статус+цена (обосновать EXPLAIN после V18).
4. Использование `search_vector_ru` в запросах вместо/вместе с `search_vector` (без нового индекса — уже есть).

Индексы добавлять только в V18+ с обоснованием и проверкой `EXPLAIN (ANALYZE, BUFFERS)`.

## N+1 problems

| Связь | Статус |
|---|---|
| Course → teacher (search `/search/*`) | **N+1** (P1): native query без join, lazy proxy инициируется по одному |
| Course → teacher (`/courses`, каталог) | OK: `join fetch` |
| Course → reviews/rating | OK: денормализованные `average_rating`/`reviews_count` |
| Tutor → availability | в поиске не участвует (пока); не допускать N+1 при добавлении availability score |
| Course → categories/tags | отдельных таблиц нет (category — колонка) |

## Ranking problems

- `exact_score` не работает (C-блок выше): prefix/contains без `%`.
- `plainto_tsquery` убивает префиксы и веса операторов (потеря `:*`).
- Веса `search_vector`: в V13 всё `'A'` (комментарий врёт про AAA/AA/A); в V17 ru — A/A/B/B.
  Нет дифференциации title > subject > description в english-векторе.
- `average_rating DESC NULLS FIRST` в каталоге (P6).
- Нет нормализации факторов (0..1) и конфигурируемых весов — только hardcoded CASE в SQL.
- Третье-четвёртое место в ORDER BY занимает `trigram_score`, считающийся от одного токена,
  пока FTS-релевантность может быть важнее.

## Filter problems

- `/api/v1/search/courses`: **все фильтры кроме status игнорируются** (C3) — критично.
- `/api/v1/courses` (JPQL `search`): фильтры работают, но `q` — только `LIKE '%q%'`,
  без FTS/синонимов/раскладки.
- `rating_min`: на `/search/*` игнорируется; на `/courses` отбрасывается вне 1..5 (без ошибки).
- `status` в native-запросах захардкожен `'APPROVED'` строкой, параметр `:status` не используется.
- Нет фильтров grade/goal/format/language/level/technology — схема курсов их пока не содержит
  (появятся через Query Understanding на поздних этапах только если будут поля в данных).

## Recommended architecture

Целевой pipeline (этапы 2–6), сохраняющий текущие контракты endpoints:

```
SearchController (/api/v1/search/courses, /api/v1/courses — без изменений контракта)
  → CourseSearchService            (оркестрация, timing, пагинация)
    → QueryUnderstandingService    (rules + synonyms → StructuredQuery; AI — optional fallback)
      → SearchQueryNormalizer      (layout/stopwords/токенизация, без багов C2/C4/C5)
      → SynonymDictionary          (вместо hardcoded Set.of; RU/KG/EN; дедуп; расширяемо)
    → CandidateSearchService       (один native-запрос: hard filters + FTS ru/en + trgm, LIMIT candidate pool)
    → RankingService               (нормализованные факторы 0..1, веса из application.yml)
    → ExplanationService           (детерминированные объяснения из фактов)
  → CourseSearchProjection         (DTO-проекция вместо entity, teacher в одном JOIN)
```

Принципы:
- Все фильтры — hard filters в SQL (`WHERE`), ranking их не отменяет.
- Один кандидатский запрос: `status + price + subject + ... AND (FTS | trgm)`, candidate_limit configurable.
- FTS: `search_vector_ru` (russian) как основной для RU/KG-кириллицы, `search_vector` (english) для EN;
  `to_tsquery`/`websearch_to_tsquery` вместо `plainto_tsquery` для управления `:*`/`&`.
- trgm для typo tolerance и частичного совпадения, по индексированным выражениям.
- Никаких LLM на простых запросах; AI-парсер optional (`search.ai.enabled=false` по умолчанию),
  возвращает только валидируемый StructuredQuery JSON.

## Expected performance

После этапов 2–6 (оценка):

- Простой запрос: 1 SQL (кандидаты ≤ 100) + 1 count (или keyset), проекция без N+1 →
  p50 < 50–100 мс, p95 < 150–250 мс на локальном PG16.
- Сейчас (оценка по SQL): 1 тяжёлый запрос с 4×LIKE '%...%' по description + N+1 teacher (20 запросов)
  + count-дубль → p50 в разы выше на больших таблицах, на малых данных маскируется размером.

Точные замеры — EXPLAIN ANALYZE + k6 на этапе 6 (до/после).

## Risks

1. **Контракт API**: фронт использует `/api/v1/courses?q=...` и `/api/v1/search/courses` —
   менять форму ответа (`Page<CourseResponse>` / `PageResponse`) нельзя; расширения только аддитивно.
2. **Flyway**: V13/V16/V17 уже применены в prod-БД; только новые V18+, без правки старых.
3. **Триггеры search_vector**: пересборка векторов на больших данных — только бэкфилл-UPDATE в миграции
   с WHERE-условием.
4. **SeedData** использует JPQL `search` — сигнатуру метода не менять.
5. **pg_trgm порог** `similarity` по умолчанию 0.3 — тюнить через `SET pg_trgm.similarity_threshold`
   в сессии осторожно, либо через `word_similarity`.
6. **KG-язык**: стандартной PG-конфигурации для кыргызского нет; использовать `russian`-стемминг
   для кириллицы + словарь KG-синонимов (не ломая RU/EN).
7. **Redis** в проекте есть, но для поиска не обязателен; не добавлять новых зависимостей (ES и т.п.).
