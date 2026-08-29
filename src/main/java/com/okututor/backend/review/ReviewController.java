package com.okututor.backend.review;

import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserService;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReviewController {

    private final ReviewService reviewService;
    private final UserService userService;

    public ReviewController(ReviewService reviewService, UserService userService) {
        this.reviewService = reviewService;
        this.userService = userService;
    }

    @GetMapping("/api/v1/courses/{courseId}/reviews")
    public Page<ReviewService.ReviewResponse> forCourse(@PathVariable UUID courseId,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return reviewService.forCourse(courseId, page, size);
    }

    @GetMapping("/api/v1/courses/{courseId}/can-review")
    public ReviewService.CanReviewResponse canReview(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID courseId) {
        if (principal == null) {
            return new ReviewService.CanReviewResponse(false, false, false);
        }
        return reviewService.canReview(principal.id(), courseId);
    }

    @PostMapping("/api/v1/courses/{courseId}/reviews")
    @PreAuthorize("hasRole('STUDENT')")
    public ReviewService.ReviewResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                               @PathVariable UUID courseId,
                                               @RequestBody(required = false) ReviewService.ReviewRequest request) {
        User student = currentUser(principal);
        return reviewService.create(student, courseId,
                request == null ? null : request.rating(),
                request == null ? null : request.comment());
    }

    @PostMapping("/api/v1/courses/{courseId}/reviews/booking/{bookingId}")
    @PreAuthorize("hasRole('STUDENT')")
    public ReviewService.ReviewResponse createForBooking(@AuthenticationPrincipal UserPrincipal principal,
                                                         @PathVariable UUID courseId,
                                                         @PathVariable UUID bookingId,
                                                         @RequestBody(required = false) ReviewService.ReviewRequest request) {
        User student = currentUser(principal);
        return reviewService.createForBooking(student, courseId, bookingId,
                request == null ? null : request.rating(),
                request == null ? null : request.comment());
    }

    private User currentUser(UserPrincipal principal) {
        if (principal == null) {
            throw com.okututor.backend.common.error.ApiException.unauthorized("Authentication required");
        }
        return userService.requireById(principal.id());
    }
}
