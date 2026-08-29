-- metadata медиа-объектов (бинарные данные в object storage, не в БД)
CREATE TABLE IF NOT EXISTS media_objects (
    id             uuid PRIMARY KEY,
    owner_user_id  uuid REFERENCES users(id) ON DELETE SET NULL,
    course_id      uuid REFERENCES courses(id) ON DELETE CASCADE,
    object_key     text NOT NULL UNIQUE,
    public_url     text NOT NULL,
    media_type     varchar(30) NOT NULL, -- AVATAR | COURSE_COVER | PROFILE
    mime_type      varchar(50),
    file_size      bigint NOT NULL DEFAULT 0,
    width          int,
    height         int,
    format         varchar(10),
    quality        int,
    created_at     timestamp NOT NULL DEFAULT now(),
    updated_at     timestamp NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_media_objects_owner ON media_objects(owner_user_id);
CREATE INDEX IF NOT EXISTS idx_media_objects_course ON media_objects(course_id);
CREATE INDEX IF NOT EXISTS idx_media_objects_type ON media_objects(media_type);

-- ссылка на обложку курса (аддитивно; фронт читает cover_url из CourseResponse)
ALTER TABLE courses ADD COLUMN IF NOT EXISTS cover_url text;
