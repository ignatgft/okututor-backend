package com.okututor.backend.tutors.availability;

import com.okututor.backend.tutors.availability.dto.AvailableSlotResponse;
import com.okututor.backend.security.UserPrincipal;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tutors/{tutorId}/available-slots")
public class AvailableSlotController {

    private final AvailableSlotService availableSlotService;

    public AvailableSlotController(AvailableSlotService availableSlotService) {
        this.availableSlotService = availableSlotService;
    }

    @GetMapping
    public ResponseEntity<List<AvailableSlotResponse>> getAvailableSlots(
            @PathVariable UUID tutorId,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam @NotNull Instant from,
            @RequestParam @NotNull Instant to,
            @RequestParam(defaultValue = "60") @Min(15) int duration) {
        // Students can view available slots for booking
        // Tutors can view their own slots
        if (!principal.id().equals(tutorId) && principal.role() != com.okututor.backend.user.Role.ADMIN) {
            // Allow students to see tutor slots for booking
            // In a real app, you might want to check if student has access
        }
        return ResponseEntity.ok(availableSlotService.getAvailableSlots(tutorId, from, to, duration));
    }
}