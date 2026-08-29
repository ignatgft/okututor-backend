-- индексы под реальные search-паттерны каталога (#8 спеки)
-- фильтр subject идёт через lower() -> функциональный индекс
CREATE INDEX IF NOT EXISTS idx_courses_subject_lower ON courses (lower(subject));
-- сортировка/фильтр каталога: статус + рейтинг + дата
CREATE INDEX IF NOT EXISTS idx_courses_status_rating_created
    ON courses (status, average_rating DESC, created_at DESC);
-- диапазон цены
CREATE INDEX IF NOT EXISTS idx_courses_price ON courses (price_per_hour);
