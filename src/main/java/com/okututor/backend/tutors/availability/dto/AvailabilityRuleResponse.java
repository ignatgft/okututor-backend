package com.okututor.backend.tutors.availability.dto;

import java.time.LocalTime;
import java.util.UUID;

public record AvailabilityRuleResponse(
        UUID id,
        String weekday,
        LocalTime startTime,
        LocalTime endTime,
        String timezone
) {}