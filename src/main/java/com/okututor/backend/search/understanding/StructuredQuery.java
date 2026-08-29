package com.okututor.backend.search.understanding;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;

/**
 * Результат понимания запроса (спека #3, #6). Только структурированные намерения —
 * без database entities и без решений о том, какой курс показать. Все извлечённые
 * значения валидируются Bean Validation; невалидный JSON AI-парсера отбрасывается.
 *
 * <p>Snake-имена полей соответствуют JSON-контракту AI-парсера и ответа API
 * ({@code extracted_filters}).
 */
public record StructuredQuery(
        Intent intent,
        String subject,
        String technology,
        String goal,
        @Min(value = 1, message = "grade must be in 1..12")
        @Max(value = 12, message = "grade must be in 1..12")
        Integer grade,
        String format,
        @DecimalMin(value = "0", message = "price_max must be >= 0")
        BigDecimal priceMax,
        @DecimalMin(value = "0", message = "price_min must be >= 0")
        BigDecimal priceMin,
        String level,
        String language,
        boolean aiAssisted) {

    public enum Intent { FIND_COURSE, FIND_TUTOR }

    public static StructuredQuery empty() {
        return new StructuredQuery(Intent.FIND_COURSE, null, null, null, null, null,
                null, null, null, null, false);
    }

    public boolean hasExtractedFilters() {
        return subject != null || technology != null || goal != null || grade != null
                || format != null || priceMax != null || priceMin != null
                || level != null || language != null;
    }

    public StructuredQuery withAiAssisted(boolean value) {
        return new StructuredQuery(intent, subject, technology, goal, grade, format,
                priceMax, priceMin, level, language, value);
    }
}
