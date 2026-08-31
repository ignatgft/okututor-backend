-- Доступность тьютора: IANA-зона, в которой интерпретируются start_time/end_time.
-- По умолчанию UTC (прежнее поведение). Существующие слоты не меняют смысла.

ALTER TABLE availability_slots
    ADD COLUMN timezone varchar(50) NOT NULL DEFAULT 'UTC';
