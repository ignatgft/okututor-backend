package com.okututor.backend.schedule.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record CheckAvailabilityRequest(
        @JsonProperty("tutor_id") @JsonAlias("tutorId") UUID tutorId,
        @JsonProperty("date") String date,
        @JsonProperty("start_time") @JsonAlias("startTime") String startTime,
        @JsonProperty("end_time") @JsonAlias("endTime") String endTime,
        @JsonProperty("timezone") String timezone
) {}
