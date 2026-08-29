# Search API (Okututor Backend)

Все ответы — snake_case. Ошибки — единый контракт `ApiError`
(`status, message, error, errors, traceId`).

## GET /api/v1/search/courses

Поиск курсов (v1, контракт стабилен).

| Параметр | Тип | Ограничения | Описание |
|---|---|---|---|
| `q` | string | ≤ 200 | свободный запрос (RU/KG/EN, опечатки, синонимы, естественный язык) |
| `subject` | string | — | точный предмет (hard filter) |
| `location_type` | string | online/offline | формат (hard filter) |
| `group_size` | string | individual/group | размер группы (hard filter) |
| `max_price` | number | ≥ 0 | верхняя граница цены (hard filter) |
| `price_min` | number | ≥ 0, ≤ max_price | нижняя граница цены (hard filter) |
| `rating_min` | number | 1..5 | минимальный рейтинг (hard filter) |
| `page` | int | ≥ 0 | страница |
| `size` | int | 1..100 | размер страницы |

Извлечённые из `q` фильтры: цена («до 1000») и формат («онлайн») — hard filters;
извлечённый предмет — soft-сигнал ранжирования (hard filter только при явном
`subject=`). Явные параметры имеют приоритет. Пустой `q`/стоп-слова → каталог
APPROVED-курсов с фильтрами (рейтинг NULLS LAST).

Ответ: `PageResponse<CourseResponse>`:
`content[], page, size, total_elements, total_pages, first, last`.

Ошибки: `422 VALIDATION_ERROR` (в т.ч. `price_min must be <= max_price`).

## GET /api/v1/search/courses/v2

То же, что v1, плюс извлечённые фильтры и объяснения (новый additive endpoint):

```json
{
  "results": [
    { "...поля CourseResponse...",
      "explanation": ["matched_subject", "matched_synonym", "price_within_budget",
                       "high_rating", "available_this_week", "personalized_for_you"] }
  ],
  "extracted_filters": {
    "intent": "FIND_COURSE", "subject": "MATHEMATICS", "technology": null,
    "goal": "ORT", "grade": 10, "format": "ONLINE", "price_max": 1000,
    "price_min": null, "level": null, "language": null, "ai_assisted": false
  },
  "page": 0, "size": 20, "total_elements": 1
}
```

Коды explanation: `exact_title_match`, `matched_text`, `matched_similar_spelling`,
`matched_subject`, `matched_synonym`, `matched_goal`, `price_within_budget`,
`high_rating`, `available_this_week`, `personalized_for_you`.

Персонализация: для аутентифицированного студента — boost по истории
(только boost, не фильтр); анонимно — идентично v1.

## GET /api/v1/search/suggestions

`?q=...` (≤ 200) → `{ "courses": [ {id,title,subject,price_per_hour,currency,
average_rating,reviews_count} ], "tutors": [ {id,full_name,avatar_url} ] }`.

## GET /api/v1/search/tutors

Заглушка (контракт сохранён): пустой `PageResponse`.

## GET /api/v1/courses (существующий)

Каталог с фильтрами — поведение и контракт не изменены.

## Поддерживаемые домены словарей (RU/KG/EN)

Кросс-языковый поиск: запрос на одном языке находит курсы на другом
(`SynonymExpander` + dual FTS ru/en + trgm). Canonical — латиница lowercase.

| Домен | Формы (примеры) | Примеры `q` |
|---|---|---|
| Технологии | python/пайтон/питон/пит, java/джава, javascript/js/node, typescript/ts, react/реакт, spring/спринг, sql/postgresql/mysql, docker/докер, go/golang, rust, kotlin/котлин, swift/свифт, c++/cpp, c#/dotnet/.net | `python`, `пит`, `kotlin` |
| Предметы | math/математика/мат/математикалык, physics/физика, chemistry/химия, biology/биология, history/история/тарых, geography/география/жография, design/дизайн | `математика`, `мат`, `физика` |
| Языки | english/английский/англис тили/агылча, russian/русский/рус тили/орусча, kyrgyz/кыргызча/кыргыз тили/кыргыз тилин | `english`, `англис тили`, `агылча` |
| Программирование | programming/программирование/программированию/coding/it/айти/программалоо | `программирование`, `python programming` |
| Мобильная разработка | mobile/мобильная разработка/ios/android/мобилка | `ios`, `android` |
| Направления | frontend/фронтенд, backend/бэкенд, machine learning/машинное обучение/ml, data science, ai/ии/нейросети | `машинное обучение`, `бэкенд` |
| Роли/формат | tutor/репетитор/мугалим/устаз/окутуучу, online/онлайн/дистанционно, offline/офлайн/оффлайн/жекеме-жеке | `репетитор по математике онлайн` |

Примеры кросс-языковых запросов:

```bash
curl "http://localhost:8080/api/v1/search/courses?q=пайтон"        # → «Python Programming»
curl "http://localhost:8080/api/v1/search/courses?q=мат"           # → «Математика ЕГЭ», «Math»
curl "http://localhost:8080/api/v1/search/courses?q=англис+тили"   # → «English for ORT»
curl "http://localhost:8080/api/v1/search/courses?q=python+programming" # technology=PYTHON + subject=PROGRAMMING
```

`technology` в `extracted_filters` (v2) — canonical технологии: `q="пайтон"` →
`"technology": "PYTHON"`.
