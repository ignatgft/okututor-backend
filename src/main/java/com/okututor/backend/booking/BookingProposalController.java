package com.okututor.backend.booking;

import com.okututor.backend.booking.dto.BookingProposalRequest;
import com.okututor.backend.booking.dto.BookingProposalResponse;
import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.user.User;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings/{bookingId}/proposals")
public class BookingProposalController {

    private final BookingProposalService proposalService;
    private final BookingRepository bookingRepository;
    private final BookingProposalRepository proposalRepository;

    public BookingProposalController(BookingProposalService proposalService,
                                     BookingRepository bookingRepository,
                                     BookingProposalRepository proposalRepository) {
        this.proposalService = proposalService;
        this.bookingRepository = bookingRepository;
        this.proposalRepository = proposalRepository;
    }

    @GetMapping
    public ResponseEntity<List<BookingProposalResponse>> listProposals(
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal UserPrincipal principal) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found"));
        if (!booking.involves(principal.id())) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(proposalRepository.findByBookingId(bookingId).stream()
                .map(BookingProposalResponse::from)
                .toList());
    }

    @PostMapping
    public ResponseEntity<BookingProposalResponse> createProposal(
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody BookingProposalRequest req) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found"));
        User user = new User();
        user.setId(principal.id());
        return ResponseEntity.ok(proposalService.createProposal(booking, user, req));
    }

    @PostMapping("/{proposalId}/accept")
    public ResponseEntity<BookingProposalResponse> acceptProposal(
            @PathVariable UUID bookingId,
            @PathVariable UUID proposalId,
            @AuthenticationPrincipal UserPrincipal principal) {
        BookingProposal proposal = proposalRepository.findByIdAndBookingId(proposalId, bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Proposal not found"));
        User user = new User();
        user.setId(principal.id());
        return ResponseEntity.ok(proposalService.acceptProposal(proposal, user));
    }

    @PostMapping("/{proposalId}/reject")
    public ResponseEntity<BookingProposalResponse> rejectProposal(
            @PathVariable UUID bookingId,
            @PathVariable UUID proposalId,
            @AuthenticationPrincipal UserPrincipal principal) {
        BookingProposal proposal = proposalRepository.findByIdAndBookingId(proposalId, bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Proposal not found"));
        User user = new User();
        user.setId(principal.id());
        return ResponseEntity.ok(proposalService.rejectProposal(proposal, user));
    }

    @PostMapping("/{proposalId}/counter")
    public ResponseEntity<BookingProposalResponse> counterProposal(
            @PathVariable UUID bookingId,
            @PathVariable UUID proposalId,
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody BookingProposalRequest req) {
        BookingProposal proposal = proposalRepository.findByIdAndBookingId(proposalId, bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Proposal not found"));
        User user = new User();
        user.setId(principal.id());
        return ResponseEntity.ok(proposalService.counterProposal(proposal, user, req));
    }
}