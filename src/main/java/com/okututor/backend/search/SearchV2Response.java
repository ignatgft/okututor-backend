package com.okututor.backend.search;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.okututor.backend.course.dto.CourseResponse;
import com.okututor.backend.search.understanding.StructuredQuery;
import java.util.List;

/**
 * Ответ поиска v2 (этап 4): results + extracted_filters + explanation (спека #17).
 * v1-контракт {@code /search/courses} не меняется; v2 — новый additive endpoint.
 */
public record SearchV2Response(
        List<CourseResult> results,
        StructuredQuery extracted_filters,
        int page,
        int size,
        long total_elements) {

    /** Поля курса разворачиваются в один уровень с explanation (@JsonUnwrapped). */
    public record CourseResult(
            @JsonUnwrapped CourseResponse course,
            List<String> explanation) {
    }
}
