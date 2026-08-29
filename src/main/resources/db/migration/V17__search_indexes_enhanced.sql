-- V17: Дополнительные индексы для интеллектуального поиска курсов
-- pg_trgm для нечёткого поиска по названию курса
-- tsvector с русской конфигурацией для FTS на русском

-- 1. Расширение pg_trgm уже создано в V13, но проверяем
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 2. GIN-индекс pg_trgm для нечёткого поиска по названию курса (title)
-- Используется оператор % для similarity search
CREATE INDEX IF NOT EXISTS idx_courses_title_trgm ON courses USING GIN (
    title gin_trgm_ops
);

-- 3. GIN-индекс pg_trgm для subject
CREATE INDEX IF NOT EXISTS idx_courses_subject_trgm ON courses USING GIN (
    subject gin_trgm_ops
);

-- 4. GIN-индекс pg_trgm для category
CREATE INDEX IF NOT EXISTS idx_courses_category_trgm ON courses USING GIN (
    category gin_trgm_ops
);

-- 5. Дополнительный tsvector с русской конфигурацией для русского языка
ALTER TABLE courses ADD COLUMN IF NOT EXISTS search_vector_ru tsvector;

UPDATE courses SET search_vector_ru =
    setweight(to_tsvector('russian', coalesce(title, '')), 'A') ||
    setweight(to_tsvector('russian', coalesce(subject, '')), 'A') ||
    setweight(to_tsvector('russian', coalesce(description, '')), 'B') ||
    setweight(to_tsvector('russian', coalesce(category, '')), 'B')
WHERE search_vector_ru IS NULL;

CREATE INDEX IF NOT EXISTS idx_courses_search_ru ON courses USING GIN (search_vector_ru);

CREATE OR REPLACE FUNCTION courses_search_vector_ru_update() RETURNS trigger AS $$
BEGIN
    NEW.search_vector_ru :=
        setweight(to_tsvector('russian', coalesce(NEW.title, '')), 'A') ||
        setweight(to_tsvector('russian', coalesce(NEW.subject, '')), 'A') ||
        setweight(to_tsvector('russian', coalesce(NEW.description, '')), 'B') ||
        setweight(to_tsvector('russian', coalesce(NEW.category, '')), 'B');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_courses_search_vector_ru ON courses;
CREATE TRIGGER trg_courses_search_vector_ru
    BEFORE INSERT OR UPDATE ON courses
    FOR EACH ROW
    EXECUTE FUNCTION courses_search_vector_ru_update();

-- 6. Композитный индекс для фильтров каталога (уже есть в V16, но проверяем)
CREATE INDEX IF NOT EXISTS idx_courses_status_rating_created
    ON courses (status, average_rating DESC, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_courses_price_per_hour ON courses (price_per_hour);

-- 7. Индекс для tutor search по имени (уже есть в V13, но проверяем)
CREATE INDEX IF NOT EXISTS idx_users_name_trgm ON users USING GIN (
    (coalesce(first_name, '') || ' ' || coalesce(last_name, '')) gin_trgm_ops
);