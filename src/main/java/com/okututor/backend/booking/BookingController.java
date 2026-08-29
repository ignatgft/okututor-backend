package com.okututor.backend.booking;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserService;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BookingController {

    public record BookingCreateRequest(UUID course_id, UUID enrollment_id, String date, String time,
                                       Integer duration_minutes, String timezone) {}

    private final BookingService bookingService;
    private final UserService userService;

    public BookingController(BookingService bookingService, UserService userService) {
        this.bookingService = bookingService;
        this.userService = userService;
    }

    @PostMapping("/api/v1/bookings")
    @PreAuthorize("hasRole('STUDENT')")
    public BookingService.BookingResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                                 @RequestBody(required = false) BookingCreateRequest request) {
        User student = currentUser(principal);
        if (request == null || request.course_id() == null) {
            throw new com.okututor.backend.common.error.FieldValidationException(
                    java.util.Map.of("course_id", "course_id is required"));
        }
        bookingService.requireAcceptedEnrollment(student.getId(), request.course_id());
        return bookingService.create(student, request.course_id(), request.enrollment_id(),
                request.date(), request.time(), request.duration_minutes(), request.timezone());
    }

    @GetMapping("/api/v1/bookings/{id}")
    public BookingService.BookingResponse byId(@AuthenticationPrincipal UserPrincipal principal,
                                               @PathVariable UUID id) {
        return bookingService.viewAs(currentUser(principal), id);
    }

    @GetMapping("/api/v1/bookings/me")
    public Page<BookingService.BookingResponse> myBookings(@AuthenticationPrincipal UserPrincipal principal,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return bookingService.myBookings(principal.id(), page, size);
    }

    @GetMapping("/api/v1/bookings/teacher")
    @PreAuthorize("hasAnyRole('TUTOR','ADMIN','SUPER_ADMIN')")
    public Page<BookingService.BookingResponse> teacherBookings(@AuthenticationPrincipal UserPrincipal principal,
                                                                @RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
        return bookingService.teacherBookings(principal.id(), page, size);
    }

    /** действия репетитора по входящим заявкам (PgTutorDashboard). */
    @PostMapping("/api/v1/bookings/{id}/confirm")
    @PreAuthorize("hasRole('TUTOR')")
    public BookingService.BookingResponse confirm(@AuthenticationPrincipal UserPrincipal principal,
                                                  @PathVariable UUID id) {
        return bookingService.confirm(currentUser(principal), id);
    }

    @PostMapping("/api/v1/bookings/{id}/reject")
    @PreAuthorize("hasRole('TUTOR')")
    public BookingService.BookingResponse reject(@AuthenticationPrincipal UserPrincipal principal,
                                                 @PathVariable UUID id) {
        return bookingService.reject(currentUser(principal), id);
    }

    @PostMapping("/api/v1/bookings/{id}/complete")
    @PreAuthorize("hasAnyRole('TUTOR','ADMIN','SUPER_ADMIN')")
    public BookingService.BookingResponse complete(@AuthenticationPrincipal UserPrincipal principal,
                                                   @PathVariable UUID id) {
        return bookingService.complete(currentUser(principal), id);
    }

    @PostMapping("/api/v1/bookings/{id}/cancel")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        bookingService.cancel(currentUser(principal), id);
        return ResponseEntity.noContent().build();
    }

    static void requireAuth(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("Authentication required");
        }
    }

    private User currentUser(UserPrincipal principal) {
        requireAuth(principal);
        return userService.requireById(principal.id());
    }
}
