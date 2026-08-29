package com.okututor.backend.tutors.availability.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record TimeOffRequest(
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        String reason
) {}