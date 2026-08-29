package com.okututor.backend.tutors.availability.dto;

import java.time.LocalDate;
import java.util.UUID;

public record TimeOffResponse(
        UUID id,
        LocalDate startDate,
        LocalDate endDate,
        String reason
) {}