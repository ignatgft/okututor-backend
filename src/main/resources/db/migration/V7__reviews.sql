-- Отзывы на курсы. Один на пару (студент, курс); опционально привязан к завершённой
-- брони. Админ может скрывать/восстанавливать без удаления.

CREATE TABLE reviews (
    id          uuid PRIMARY KEY,
    course_id   uuid NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    student_id  uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    booking_id  uuid REFERENCES bookings(id) ON DELETE SET NULL,
    rating      int NOT NULL,
    comment     text,
    hidden      boolean NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_review_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT uq_review_student_course UNIQUE (course_id, student_id)
);

CREATE INDEX idx_reviews_course ON reviews(course_id);
