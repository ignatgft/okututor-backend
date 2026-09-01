-- Добавляем проверяемый порядок занятий внутри расписания (1..N), требуется для инварианта 8→8.
ALTER TABLE lessons ADD COLUMN sequence_number int;
CREATE INDEX idx_lessons_schedule_sequence ON lessons(schedule_id, sequence_number);
-- существующие записи (seed) получат NULL, новые — 1..N
