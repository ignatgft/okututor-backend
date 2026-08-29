package com.okututor.backend.tutors.availability.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record BlockedTimeRequest(
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        String reason
) {}