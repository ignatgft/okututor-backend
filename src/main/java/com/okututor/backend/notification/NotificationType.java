package com.okututor.backend.notification;

/**
 * Предсказуемые типы уведомлений (строка в БД остаётся varchar — это контракт для фронта).
 * Все события пользовательского пути: заявки→занятия→статистика, заявка «стать тьютором».
 */
public final class NotificationType {

    private NotificationType() {
    }

    // ----- заявки на курс -----
    public static final String COURSE_APPLICATION = "COURSE_APPLICATION";
    public static final String APPLICATION_ACCEPTED = "APPLICATION_ACCEPTED";
    public static final String APPLICATION_REJECTED = "APPLICATION_REJECTED";
    public static final String APPLICATION_CANCELLED = "APPLICATION_CANCELLED";

    // ----- брони / занятия -----
    public static final String BOOKING_CONFIRMED = "BOOKING_CONFIRMED";
    public static final String BOOKING_REJECTED = "BOOKING_REJECTED";
    public static final String BOOKING_CANCELLED = "BOOKING_CANCELLED";
    public static final String BOOKING_COMPLETED = "BOOKING_COMPLETED";

    // ----- заявка «стать тьютором» -----
    public static final String TUTOR_APPLICATION_APPROVED = "TUTOR_APPLICATION_APPROVED";
    public static final String TUTOR_APPLICATION_REJECTED = "TUTOR_APPLICATION_REJECTED";

    // ----- системные -----
    public static final String MESSAGE = "MESSAGE";
    public static final String SYSTEM = "SYSTEM";
}
