package com.okututor.backend.support.dto;

import jakarta.validation.constraints.NotBlank;

/** тело POST /support/tickets (TicketCreateRequest из docs/mapping.md #52). */
public record SupportTicketCreateRequest(
        @NotBlank(message = "One of TECHNICAL/PAYMENT/COURSE/ACCOUNT/BUG") String category,
        @NotBlank(message = "Subject is required") String subject,
        @NotBlank(message = "Description is required") String description,
        String priority
) {}
