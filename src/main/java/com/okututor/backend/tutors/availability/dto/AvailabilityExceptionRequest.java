package com.okututor.backend.tutors.availability.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

public record AvailabilityExceptionRequest(
        @NotNull LocalDate date,
        @NotNull Boolean available,
        LocalTime startTime,
        LocalTime endTime
) {}