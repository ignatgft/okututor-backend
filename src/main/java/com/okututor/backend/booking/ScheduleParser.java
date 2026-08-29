package com.okututor.backend.booking;

import com.okututor.backend.common.error.ApiException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * Разбор локальных date/time из запросов в Instant.
 *
 * Контракт с фронтом (см. docs/mapping.md): date (yyyy-MM-dd) и time (HH:mm|HH:mm:ss)
 * — это ЛОКАЛЬНЫЕ часы пользователя. Если запрос содержит IANA-зону (timezone,
 * напр. "Asia/Bishkek"), Instant считается как LocalDateTime.atZone(zone).toInstant();
 * если зоны нет — используется ZoneOffset.UTC (прежнее поведение), о чём фронт знает.
 */
public final class ScheduleParser {

    /** валидные длительности занятия (мин). */
    public static final Set<Integer> VALID_DURATIONS = Set.of(30, 45, 60, 90, 120);

    private static final DateTimeFormatter TIME_ISO = DateTimeFormatter.ISO_LOCAL_TIME;
    private static final DateTimeFormatter TIME_HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter TIME_HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private ScheduleParser() {
    }

    /**
     * @param date    yyyy-MM-dd (локальные часы)
     * @param time    HH:mm или HH:mm:ss
     * @param timezone IANA-зона (nullable → UTC)
     */
    public static Instant combine(String date, String time, String timezone) {
        LocalDate d = parseDate(date);
        LocalTime t = parseTime(time);
        ZoneId zone = parseZone(timezone);
        try {
            return d.atTime(t).atZone(zone).toInstant();
        } catch (DateTimeException e) {
            throw ApiException.validation("Invalid date/time combination");
        }
    }

    public static LocalDate parseDate(String date) {
        try {
            return LocalDate.parse((date == null ? "" : date.trim()), DATE_ISO);
        } catch (DateTimeParseException e) {
            throw ApiException.validation("date must be yyyy-MM-dd");
        }
    }

    public static LocalTime parseTime(String time) {
        String raw = time == null ? "" : time.trim();
        if (raw.isEmpty()) {
            throw ApiException.validation("time is required (HH:mm)");
        }
        // явные форматы HH:mm и HH:mm:ss; ISO_LOCAL_TIME тоже принимает их
        for (DateTimeFormatter fmt : java.util.List.of(TIME_HM, TIME_HMS, TIME_ISO)) {
            try {
                return LocalTime.parse(raw, fmt);
            } catch (DateTimeParseException ignored) {
                // пробуем следующий формат
            }
        }
        throw ApiException.validation("time must be HH:mm (or HH:mm:ss)");
    }

    public static ZoneId parseZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException e) {
            throw ApiException.validation("Unknown timezone: " + timezone);
        }
    }

    public static void requireDuration(int duration) {
        if (!VALID_DURATIONS.contains(duration)) {
            throw ApiException.validation("duration_minutes must be one of " + VALID_DURATIONS);
        }
    }
}
