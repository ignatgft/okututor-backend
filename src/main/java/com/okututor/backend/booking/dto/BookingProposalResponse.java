package com.okututor.backend.booking.dto;

import com.okututor.backend.booking.BookingProposal;
import java.time.Instant;
import java.util.UUID;

public record BookingProposalResponse(
        UUID id,
        UUID bookingId,
        UUID proposedById,
        Instant startAt,
        Instant endAt,
        BookingProposal.Status status,
        Instant expiresAt,
        Instant createdAt
) {
    public static BookingProposalResponse from(BookingProposal p) {
        return new BookingProposalResponse(
                p.getId(),
                p.getBooking() != null ? p.getBooking().getId() : null,
                p.getProposedBy() != null ? p.getProposedBy().getId() : null,
                p.getStartAt(),
                p.getEndAt(),
                p.getStatus(),
                p.getExpiresAt(),
                p.getCreatedAt()
        );
    }
}