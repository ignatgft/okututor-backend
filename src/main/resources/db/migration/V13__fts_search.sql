-- Full-text search (FTS) для courses: tsvector + GIN индекс.
-- Для tutor search по имени используем pg_trgm (triogram similarity).

-- 1. Расширение pg_trgm (trigram matching для нечёткого поиска имён).
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 2. Добавляем tsvector-колонку в courses.
ALTER TABLE courses ADD COLUMN search_vector tsvector;

-- 3. Заполняем search_vector: веса title=AAA, subject=AA, description=A.
UPDATE courses SET search_vector =
    setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(subject, '')), 'A') ||
    setweight(to_tsvector('english', coalesce(description, '')), 'A');

-- 4. GIN-индекс для быстрого FTS-поиска по courses.
CREATE INDEX idx_courses_search ON courses USING GIN (search_vector);

-- 5. Триггер: автоматическое обновление search_vector при INSERT/UPDATE.
CREATE OR REPLACE FUNCTION courses_search_vector_update() RETURNS trigger AS $$
BEGIN
    NEW.search_vector :=
        setweight(to_tsvector('english', coalesce(NEW.title, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(NEW.subject, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(NEW.description, '')), 'A');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_courses_search_vector
    BEFORE INSERT OR UPDATE ON courses
    FOR EACH ROW
    EXECUTE FUNCTION courses_search_vector_update();

-- 6. GIN-индекс pg_trgm для нечёткого поиска по именам пользователей (tutors).
CREATE INDEX idx_users_name_trgm ON users USING GIN (
    (coalesce(first_name, '') || ' ' || coalesce(last_name, '')) gin_trgm_ops
);
