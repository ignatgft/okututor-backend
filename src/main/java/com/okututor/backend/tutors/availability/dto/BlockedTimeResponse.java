package com.okututor.backend.tutors.availability.dto;

import java.time.Instant;
import java.util.UUID;

public record BlockedTimeResponse(
        UUID id,
        Instant startAt,
        Instant endAt,
        String reason
) {}