package com.okututor.backend.dto.course;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateReviewRequest(
    @JsonProperty("student_id") @NotBlank String studentId,
    @NotNull @Min(1) @Max(5) Integer rating,
    @NotBlank String comment
) {
}

