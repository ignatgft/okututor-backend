-- V18: оптимизация поиска (этап 2).
-- Обоснование каждого изменения — в docs/SEARCH_AUDIT.md (Recommended architecture).

-- 1. Дифференциация весов english-вектора: title > subject/category > description.
--    В V13 все поля получили вес 'A', из-за чего совпадение в описании ранжировалось
--    так же высоко, как совпадение в названии.
CREATE OR REPLACE FUNCTION courses_search_vector_update() RETURNS trigger AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('english', coalesce(NEW.title, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(NEW.subject, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(NEW.category, '')), 'B') ||
        setweight(to_tsvector('english', coalesce(NEW.description, '')), 'C');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

UPDATE courses SET search_vector =
    setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(subject, '')), 'B') ||
    setweight(to_tsvector('english', coalesce(category, '')), 'B') ||
    setweight(to_tsvector('english', coalesce(description, '')), 'C');

-- 2. Русский вектор: description понижен до 'C' (title/subject важнее).
CREATE OR REPLACE FUNCTION courses_search_vector_ru_update() RETURNS trigger AS $$
BEGIN
    NEW.search_vector_ru :=
        setweight(to_tsvector('russian', coalesce(NEW.title, '')), 'A') ||
        setweight(to_tsvector('russian', coalesce(NEW.subject, '')), 'A') ||
        setweight(to_tsvector('russian', coalesce(NEW.category, '')), 'B') ||
        setweight(to_tsvector('russian', coalesce(NEW.description, '')), 'C');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

UPDATE courses SET search_vector_ru =
    setweight(to_tsvector('russian', coalesce(title, '')), 'A') ||
    setweight(to_tsvector('russian', coalesce(subject, '')), 'A') ||
    setweight(to_tsvector('russian', coalesce(category, '')), 'B') ||
    setweight(to_tsvector('russian', coalesce(description, '')), 'C');

-- 3. GIN trgm по lower(title): синоним/typo-путь использует lower(title) ~ regex и
--    lower(title) % token. Существующий idx_courses_title_trgm построен по title без
--    lower() и для этих предикатов неприменим (аудит: Missing indexes #1).
CREATE INDEX IF NOT EXISTS idx_courses_title_lower_trgm ON courses USING GIN (
    lower(title) gin_trgm_ops
);

-- 4. Парциальный индекс каталога APPROVED с NULLS LAST: основной запрос без q —
--    каталог по рейтингу. Прежний порядок (DESC NULLS FIRST) показывал курсы без
--    рейтинга первыми (аудит: P6).
CREATE INDEX IF NOT EXISTS idx_courses_approved_rating_created
    ON courses (average_rating DESC NULLS LAST, created_at DESC)
    WHERE status = 'APPROVED';

-- 5. Дублирующий индекс: idx_courses_price (V16) на той же колонке, что и
--    idx_courses_price_per_hour (V17) (аудит: P5).
DROP INDEX IF EXISTS idx_courses_price;
