package com.okututor.backend.booking;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.booking.dto.BookingProposalRequest;
import com.okututor.backend.booking.dto.BookingProposalResponse;
import com.okututor.backend.user.User;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BookingProposalService {

    private static final long PROPOSAL_TTL_MINUTES = 60;

    private final BookingProposalRepository proposalRepository;
    private final BookingRepository bookingRepository;

    public BookingProposalService(BookingProposalRepository proposalRepository,
                                  BookingRepository bookingRepository) {
        this.proposalRepository = proposalRepository;
        this.bookingRepository = bookingRepository;
    }

    public BookingProposalResponse createProposal(Booking booking, User proposer, BookingProposalRequest req) {
        // Only student or teacher of the booking can propose
        if (!booking.involves(proposer.getId())) {
            throw ApiException.forbidden("Not a participant of this booking");
        }

        // Booking must be in a state that allows proposals
        if (booking.getStatus() != Booking.Status.PENDING
                && booking.getStatus() != Booking.Status.CONFIRMED) {
            throw ApiException.conflict("Cannot propose for booking in status " + booking.getStatus());
        }

        // Check for overlapping active proposals
        List<BookingProposal> active = proposalRepository.findActiveProposalsForBooking(booking.getId(), Instant.now());
        if (!active.isEmpty()) {
            throw ApiException.conflict("There is already an active proposal for this booking");
        }

        // Check availability - no overlapping bookings for teacher or student
        List<Booking.Status> activeStatuses = List.of(Booking.Status.PENDING, Booking.Status.CONFIRMED);
        boolean teacherBusy = bookingRepository.overlapsTeacher(booking.getTeacher().getId(), activeStatuses,
                req.startAt(), req.endAt());
        boolean studentBusy = bookingRepository.overlapsStudent(booking.getStudent().getId(), activeStatuses,
                req.startAt(), req.endAt());

        if (teacherBusy || studentBusy) {
            throw ApiException.conflict("Proposed time conflicts with existing booking");
        }

        BookingProposal proposal = new BookingProposal();
        proposal.setBooking(booking);
        proposal.setProposedBy(proposer);
        proposal.setStartAt(req.startAt());
        proposal.setEndAt(req.endAt());
        proposal.setExpiresAt(Instant.now().plusSeconds(TimeUnit.MINUTES.toSeconds(PROPOSAL_TTL_MINUTES)));
        return BookingProposalResponse.from(proposalRepository.save(proposal));
    }

    public BookingProposalResponse acceptProposal(BookingProposal proposal, User user) {
        // Only the other party (not the proposer) can accept
        if (proposal.getProposedBy().getId().equals(user.getId())) {
            throw ApiException.forbidden("Cannot accept your own proposal");
        }
        if (!proposal.getBooking().involves(user.getId())) {
            throw ApiException.forbidden("Not a participant of this booking");
        }
        if (proposal.getStatus() != BookingProposal.Status.PENDING) {
            throw ApiException.conflict("Proposal is not pending");
        }
        if (proposal.getExpiresAt().isBefore(Instant.now())) {
            proposal.setStatus(BookingProposal.Status.EXPIRED);
            proposalRepository.save(proposal);
            throw ApiException.conflict("Proposal has expired");
        }

        // Update booking with new time
        Booking booking = proposal.getBooking();
        booking.setStartAt(proposal.getStartAt());
        booking.setEndAt(proposal.getEndAt());

        // Transition booking status based on current state
        if (booking.getStatus() == Booking.Status.PENDING) {
            booking.transitionTo(Booking.Status.CONFIRMED);
        } else if (booking.getStatus() == Booking.Status.CONFIRMED) {
            booking.transitionTo(Booking.Status.RESCHEDULED);
        }

        bookingRepository.save(booking);

        // Mark proposal as accepted
        proposal.setStatus(BookingProposal.Status.ACCEPTED);
        proposalRepository.save(proposal);

        // Reject other pending proposals for this booking
        rejectOtherProposals(booking.getId(), proposal.getId());

        return BookingProposalResponse.from(proposal);
    }

    public BookingProposalResponse rejectProposal(BookingProposal proposal, User user) {
        if (!proposal.getBooking().involves(user.getId())) {
            throw ApiException.forbidden("Not a participant of this booking");
        }
        if (proposal.getStatus() != BookingProposal.Status.PENDING) {
            throw ApiException.conflict("Proposal is not pending");
        }

        proposal.setStatus(BookingProposal.Status.REJECTED);
        return BookingProposalResponse.from(proposalRepository.save(proposal));
    }

    public BookingProposalResponse counterProposal(BookingProposal proposal, User user, BookingProposalRequest req) {
        // Only the other party can counter
        if (proposal.getProposedBy().getId().equals(user.getId())) {
            throw ApiException.forbidden("Cannot counter your own proposal");
        }
        if (!proposal.getBooking().involves(user.getId())) {
            throw ApiException.forbidden("Not a participant of this booking");
        }
        if (proposal.getStatus() != BookingProposal.Status.PENDING) {
            throw ApiException.conflict("Proposal is not pending");
        }
        if (proposal.getExpiresAt().isBefore(Instant.now())) {
            proposal.setStatus(BookingProposal.Status.EXPIRED);
            proposalRepository.save(proposal);
            throw ApiException.conflict("Proposal has expired");
        }

        // Check availability for new time
        List<Booking.Status> activeStatuses = List.of(Booking.Status.PENDING, Booking.Status.CONFIRMED);
        boolean teacherBusy = bookingRepository.overlapsTeacher(proposal.getBooking().getTeacher().getId(), activeStatuses,
                req.startAt(), req.endAt());
        boolean studentBusy = bookingRepository.overlapsStudent(proposal.getBooking().getStudent().getId(), activeStatuses,
                req.startAt(), req.endAt());

        if (teacherBusy || studentBusy) {
            throw ApiException.conflict("Proposed time conflicts with existing booking");
        }

        // Mark old proposal as countered
        proposal.setStatus(BookingProposal.Status.COUNTERED);
        proposalRepository.save(proposal);

        // Create new counter-proposal
        return createProposal(proposal.getBooking(), user, req);
    }

    private void rejectOtherProposals(UUID bookingId, UUID acceptedProposalId) {
        List<BookingProposal> others = proposalRepository.findByBookingId(bookingId);
        for (BookingProposal p : others) {
            if (!p.getId().equals(acceptedProposalId) && p.getStatus() == BookingProposal.Status.PENDING) {
                p.setStatus(BookingProposal.Status.REJECTED);
                proposalRepository.save(p);
            }
        }
    }
}