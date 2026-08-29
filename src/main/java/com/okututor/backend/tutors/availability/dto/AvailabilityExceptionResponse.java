package com.okututor.backend.tutors.availability.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record AvailabilityExceptionResponse(
        UUID id,
        LocalDate date,
        Boolean available,
        LocalTime startTime,
        LocalTime endTime
) {}