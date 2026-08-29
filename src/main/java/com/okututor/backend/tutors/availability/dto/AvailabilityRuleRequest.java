package com.okututor.backend.tutors.availability.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;

public record AvailabilityRuleRequest(
        @NotBlank String weekday,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        String timezone
) {}