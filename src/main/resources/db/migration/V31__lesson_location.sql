-- Офлайн-занятия: место проведения (тип места + адрес + детали).
-- Заполняется при OFFLINE-формате; для ONLINE остаётся NULL.

ALTER TABLE lessons
    ADD COLUMN location_type varchar(20),
    ADD COLUMN location_address text,
    ADD COLUMN location_details text;

ALTER TABLE schedules
    ADD COLUMN location_type varchar(20),
    ADD COLUMN location_address text,
    ADD COLUMN location_details text;
