package com.okututor.backend.booking.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record BookingProposalRequest(
        @NotNull Instant startAt,
        @NotNull Instant endAt
) {}