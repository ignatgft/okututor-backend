package com.okututor.backend.lesson.dto;

import com.okututor.backend.lesson.Lesson;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

/**
 * Локализованные подписи статусов урока (Вариант А — бэкенд отдаёт statusLabel).
 * Поддерживает ru / ky / en, fallback ru.
 * Определяет язык по Accept-Language заголовку (первый язык) или locale параметру.
 */
@Service
public class LessonStatusLabelService {

    private static final Map<String, Map<String, String>> LABELS = Map.of(
            "ru", Map.ofEntries(
                    Map.entry("SCHEDULED", "Подтверждено"),
                    Map.entry("CONFIRMED", "Подтверждено"),
                    Map.entry("PENDING", "Ожидает подтверждения"),
                    Map.entry("PENDING_CONFIRMATION", "Ожидает подтверждения"),
                    Map.entry("IN_PROGRESS", "Идёт сейчас"),
                    Map.entry("COMPLETED", "Завершено"),
                    Map.entry("CANCELLED", "Отменено"),
                    Map.entry("RESCHEDULED", "Перенесено"),
                    Map.entry("REJECTED", "Отклонено"),
                    Map.entry("NO_SHOW", "Не явился"),
                    Map.entry("PROPOSED", "Предложено")
            ),
            "en", Map.ofEntries(
                    Map.entry("SCHEDULED", "Confirmed"),
                    Map.entry("CONFIRMED", "Confirmed"),
                    Map.entry("PENDING", "Pending confirmation"),
                    Map.entry("PENDING_CONFIRMATION", "Pending confirmation"),
                    Map.entry("IN_PROGRESS", "In progress"),
                    Map.entry("COMPLETED", "Completed"),
                    Map.entry("CANCELLED", "Cancelled"),
                    Map.entry("RESCHEDULED", "Rescheduled"),
                    Map.entry("REJECTED", "Rejected"),
                    Map.entry("NO_SHOW", "No show"),
                    Map.entry("PROPOSED", "Proposed")
            ),
            "ky", Map.ofEntries(
                    Map.entry("SCHEDULED", "Тастыкталды"),
                    Map.entry("CONFIRMED", "Тастыкталды"),
                    Map.entry("PENDING", "Тастыктоону күтүүдө"),
                    Map.entry("PENDING_CONFIRMATION", "Тастыктоону күтүүдө"),
                    Map.entry("IN_PROGRESS", "Азыр өтүүдө"),
                    Map.entry("COMPLETED", "Аяктады"),
                    Map.entry("CANCELLED", "Жокко чыгарылды"),
                    Map.entry("RESCHEDULED", "Жылдырылды"),
                    Map.entry("REJECTED", "Четке кагылды"),
                    Map.entry("NO_SHOW", "Келген жок"),
                    Map.entry("PROPOSED", "Сунушталды")
            )
    );

    public String labelFor(String status, Locale locale) {
        String lang = resolveLang(locale);
        Map<String, String> map = LABELS.getOrDefault(lang, LABELS.get("ru"));
        if (status == null) return map.getOrDefault("SCHEDULED", "Подтверждено");
        String key = status.trim().toUpperCase(Locale.ROOT);
        return map.getOrDefault(key, map.getOrDefault("SCHEDULED", key));
    }

    public String labelFor(Lesson.Status status, Locale locale) {
        if (status == null) return labelFor("SCHEDULED", locale);
        return labelFor(status.name(), locale);
    }

    public String labelForBooking(String bookingStatus, Locale locale) {
        return labelFor(bookingStatus, locale);
    }

    private String resolveLang(Locale locale) {
        if (locale == null) return "ru";
        String lang = locale.getLanguage();
        if (lang == null || lang.isBlank()) return "ru";
        lang = lang.toLowerCase(Locale.ROOT);
        if (LABELS.containsKey(lang)) return lang;
        // Accept-Language может быть "ru-RU,ky;q=0.9" — берём первое
        if (lang.contains("-")) {
            String base = lang.split("-")[0];
            if (LABELS.containsKey(base)) return base;
        }
        if (lang.contains(",")) {
            String base = lang.split(",")[0].trim().toLowerCase(Locale.ROOT);
            if (LABELS.containsKey(base)) return base;
            if (base.contains("-")) {
                base = base.split("-")[0];
                if (LABELS.containsKey(base)) return base;
            }
        }
        return "ru";
    }

    /** Парсит Accept-Language заголовок вида "ru, en;q=0.8" в Locale */
    public Locale parseAcceptLanguage(String header) {
        if (header == null || header.isBlank()) return new Locale("ru");
        String first = header.split(",")[0].trim();
        if (first.contains(";")) first = first.split(";")[0].trim();
        if (first.contains("-")) first = first.split("-")[0].trim();
        String lang = first.toLowerCase(Locale.ROOT);
        if (LABELS.containsKey(lang)) return new Locale(lang);
        return new Locale("ru");
    }
}
