package com.okututor.backend.course.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.List;

/**
 * тело POST /courses (CourseWizard, см. docs/mapping.md #25).
 * days/specific_days фронт присылает массивом строк — собираем в CSV для БД;
 * ACCEPT_SINGLE_VALUE_AS_ARRAY терпит и одиночную строку.
 */
public record CourseCreateRequest(
        @NotBlank(message = "Title is required") String title,
        String description,
        @NotBlank(message = "Subject is required") String subject,
        String category,
        BigDecimal price_per_hour,
        String currency,
        @NotBlank(message = "location_type is required") String location_type,
        @NotBlank(message = "group_size is required") String group_size,
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> days,
        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> specific_days,
        Integer experience,
        Integer max_students,
        String status
) {}
