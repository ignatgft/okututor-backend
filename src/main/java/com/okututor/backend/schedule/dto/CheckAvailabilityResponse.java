package com.okututor.backend.schedule.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record CheckAvailabilityResponse(
        boolean available,
        String reason,
        String message,
        @JsonProperty("suggestedSlots") List<String> suggestedSlots
) {
    public static CheckAvailabilityResponse ok() {
        return new CheckAvailabilityResponse(true, "OK", "Свободно", List.of());
    }
}
