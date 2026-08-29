# Search Architecture (Okututor Backend)

Итоговая архитектура поиска курсов (этапы 1–6). Аудит и история проблем — в
`SEARCH_AUDIT.md`.

## Pipeline

```
HTTP-запрос (q, subject, location_type, group_size, max_price, price_min, rating_min, page, size)
  │
  ├─ 1. VALIDATION            Bean Validation параметров → 422
  ├─ 2. NORMALIZATION         KeyboardLayoutNormalizer → SearchQueryNormalizer
  │      (lowercase, стоп-слова, токены, fts-строка для to_tsquery, лимит 200)
  ├─ 3. QUERY UNDERSTANDING   RuleBasedQueryParser (+ optional AI) → StructuredQuery
  │      (intent, subject, technology, goal, grade, format, price_max/min, level)
  ├─ 4. FILTER MERGE          явные параметры API > извлечённые из запроса
  ├─ 5. HARD FILTERS (SQL)    status/subject/location/group/price/rating — только PostgreSQL
  ├─ 6. CANDIDATE RETRIEVAL   FTS ru/en (to_tsquery, префиксы) + pg_trgm (опечатки)
  │      + синоним-regex; LIMIT search.candidate-limit (пул кандидатов)
  ├─ 7. AVAILABILITY          slots − active bookings → 0..1 на репетитора (батч)
  ├─ 8. PERSONALIZATION       история бронирований/зачислений → boost (не фильтр)
  ├─ 9. RANKING               RankingService: нормализованные факторы 0..1 × веса
  └─ 10. PAGINATION + RESPONSE  v1: PageResponse<CourseResponse>
                                v2: results + extracted_filters + explanation
```

## Ключевые принципы

1. **Фильтры — только hard filters в SQL.** Ranking никогда не отменяет фильтры.
2. **Кандидатский пул.** PostgreSQL возвращает `candidate-limit` (100) кандидатов;
   ранжирование работает по пулу, пагинация — по отранжированному списку.
3. **Без N+1.** Кандидаты — DTO-проекция `CourseSearchProjection` с teacher в одном
   JOIN; каталог — SQL-пагинация по id + батч-загрузка teacher; доступность и
   персонализация — батч-запросами.
4. **Текстовый поиск:** `to_tsquery` с префиксами `:*` (а не `plainto_tsquery`),
   два вектора (`search_vector` english, `search_vector_ru` russian) с весами
   title A / subject A(ru)-B(en) / category B / description C; `pg_trgm` для опечаток.
5. **Query Understanding без внешних зависимостей.** Rules+словари RU/KG/EN всегда;
   AI — optional (`search.ai.enabled`, fallback на rules при любой ошибке,
   возвращает только провалидированный StructuredQuery).
6. **Персонализация — только boost, никогда фильтр.** Анонимная выдача идентична
   при любом `personalization-weight`.
7. **Объяснения детерминированные** — выводятся из фактических сигналов, не из LLM.

## Текстовые сигналы (SQL)

| Сигнал | Предикат | Индекс |
|---|---|---|
| FTS russian | `search_vector_ru @@ to_tsquery('russian', :q)` | `idx_courses_search_ru` (GIN) |
| FTS english | `search_vector @@ to_tsquery('english', :q)` | `idx_courses_search` (GIN) |
| Опечатки | `lower(title) % lower(:token)` | `idx_courses_title_lower_trgm` (GIN, V18) |
| Синонимы | `lower(col) ~ :regex_alternatives` | trgm по title; seq по остальным на малых данных |

Извлечённый из запроса subject — **soft-сигнал** (ranking `subjectFactor` + объяснения),
не hard-фильтр: при свободных subject-метках курсов hard-фильтр давал ложные отсечения.
Hard-фильтр по subject применяется только при явном API-параметре `subject=`.

## Cross-lingual retrieval

Запрос на RU/KG/EN находит курсы с title/subject/description на любом языке:

1. **Dual-language FTS всегда.** Кандидатский запрос — OR по `search_vector_ru`
   (russian) И `search_vector` (english) + trgm + synonym regex. Язык запроса
   не выбирает язык поиска.
2. **Словари `SynonymExpander`** — кросс-языковые группы: canonical (латиница
   lowercase, первый элемент) + RU + KG + склонения. Обратный индекс
   alias→canonical даёт O(1) lookup на токен; многословные алиасы
   («англис тили», «машинное обучение») матчатся предкомпилированными
   паттернами по строке запроса (или склейке токенов) с границами слов.
   Алиас, занятый другой группой, не перезаписывается (putIfAbsent).
   Расширение словаря без деплоя: `search.synonyms.groups` в application.yml
   (`[[canonical, alias...], ...]`).
3. **Technology extraction.** Токен из tech-группы → `StructuredQuery.technology`
   = canonical (PYTHON, JAVA, JAVASCRIPT, KOTLIN, SWIFT, ...). «programming» →
   `subject=PROGRAMMING`, не затирает technology.
4. **Tech-restricted candidate OR.** При извлечённой технологии synonym OR-ветка
   строится только из алиасов технологии: общие токены («programming») не могут
   быть единственным матчем — иначе `q="java"` размывался бы курсами
   «Программирование для детей». Без технологии — все expanded-токены.
5. **Exact/prefix boost.** В `textFactor`: точный токен запроса в названии →
   `exact-title-bonus` (0.9); технология в названии → `technology-title-bonus`
   (0.8); exact match всего запроса → 1.0. Совпадение языка запроса и названия
   НЕ бустится — только фактическое совпадение токенов.

## Факторы ранжирования (веса — `search.ranking.*`)

| Фактор | Вес | Нормализация |
|---|---|---|
| text | 0.45 | exact match → 1.0; точный токен в title → 0.9; технология в title → 0.8; иначе max(ts_rank_cd, trgm) |
| subject | 0.20 | subject курса содержит токен/синоним/алиас технологии → 1.0 |
| rating | 0.15 | rating/5; без рейтинга → 0 |
| reviews | 0.10 | reviews/(reviews+10) — насыщение |
| availability | 0.10 | доля свободных дней за 7 дней (slots − active bookings) |
| personalization | 0.05 | предмет истории → 1.0; знакомый репетитор → 0.6 |

Итог = Σ(wᵢ·fᵢ)/Σwᵢ (по активным весам); tiebreaker — `id DESC` (стабильный порядок).

## Миграции

- `V13` — FTS-вектор, триггеры, GIN-индексы
- `V16`/`V17` — индексы фильтров и trgm
- `V18` — веса FTS (title>A/subject,category>B/description>C), trgm по `lower(title)`,
  парциальный индекс каталога `(average_rating DESC NULLS LAST, created_at DESC)
  WHERE status='APPROVED'`, удалён дублирующий `idx_courses_price`

## Конфигурация (`application.yml`, prefix `search`)

```yaml
search:
  max-query-length: 200
  default-page-size: 20
  max-page-size: 100
  candidate-limit: 100
  ranking: { text-weight: 0.45, subject-weight: 0.20, rating-weight: 0.15,
             review-weight: 0.10, availability-weight: 0.10,
             personalization-weight: 0.05, review-saturation: 10,
             exact-title-bonus: 0.9, technology-title-bonus: 0.8 }
  ai: { enabled: false, provider: "", model: "", timeout-ms: 3000, max-tokens: 500 }
  personalization: { enabled: true }
  embeddings: { enabled: false }
```

AI-парсер активируется `search.ai.enabled=true` + `OKUTUTOR_SEARCH_AI_ENDPOINT`
+ `OKUTUTOR_SEARCH_AI_API_KEY` (OpenAI-совместимый chat-completions).

## Расширение

- Новый синоним/предмет/цель → словари `SynonymExpander` / `RuleBasedQueryParser`
  или без деплоя: `search.synonyms.groups` в application.yml
  (+ `search_synonyms` в БД для динамических).
- Новый фактор ранжирования → метод в `RankingService` + вес в `SearchProperties.Ranking`.
- Embeddings/векторный поиск → `search.embeddings.*` зарезервирован, по умолчанию выключен.
