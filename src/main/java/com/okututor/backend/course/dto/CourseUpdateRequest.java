package com.okututor.backend.course.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.util.List;

/**
 * частичное обновление курса (PUT /courses/{id}): null = поле не менять.
 * Валидация значений enum/диапазонов — в сервисе, чтобы сохранить
 * прежние тексты ошибок полей (422 VALIDATION_ERROR).
 */
public record CourseUpdateRequest(
        String title,
        String description,
        String subject,
        String category,
        BigDecimal price_per_hour,
        String currency,
        String location_type,
        String group_size,
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> days,
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> specific_days,
        Integer experience,
        Integer max_students,
        String status
) {}
