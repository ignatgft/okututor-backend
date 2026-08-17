package com.okututor.backend.dto.course;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CourseCreateRequest(
    @JsonProperty("user_id") @NotBlank String userId,
    @NotBlank String title,
    @NotBlank String description,
    @NotBlank String days,
    @JsonProperty("specific_days") String specificDays,
    @JsonProperty("group_size") @NotBlank String groupSize,
    @JsonProperty("location_type") @NotBlank String locationType,
    @NotNull Integer experience,
    @JsonProperty("price_per_hour") @NotNull Double pricePerHour
) {
}

