package com.okututor.backend.dto.meeting;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record CreateMeetingRequest(
    @NotBlank String topic,
    @JsonProperty("start_time") @NotNull Instant startTime,
    @NotNull @Min(1) Integer duration
) {
}
