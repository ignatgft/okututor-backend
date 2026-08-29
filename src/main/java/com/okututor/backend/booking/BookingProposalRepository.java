package com.okututor.backend.booking;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingProposalRepository extends JpaRepository<BookingProposal, UUID> {
    List<BookingProposal> findByBookingId(UUID bookingId);

    Optional<BookingProposal> findByIdAndBookingId(UUID proposalId, UUID bookingId);

    @Query("select bp from BookingProposal bp where bp.booking.id = :bookingId and bp.status = 'PENDING' and bp.expiresAt > :now")
    List<BookingProposal> findActiveProposalsForBooking(@Param("bookingId") UUID bookingId, @Param("now") Instant now);
}