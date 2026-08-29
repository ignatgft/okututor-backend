package com.okututor.backend.tutors.availability.dto;

import java.time.Instant;

public record AvailableSlotResponse(
        Instant startAt,
        Instant endAt,
        int durationMinutes
) {}