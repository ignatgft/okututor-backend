alter table users add column if not exists timezone varchar(50) default 'UTC';
