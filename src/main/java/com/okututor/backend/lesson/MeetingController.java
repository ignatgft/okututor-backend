package com.okututor.backend.lesson;

import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * join/leave LiveKit для BOOKING — контракт, на который опирается PgLesson.jsx:
 *   POST /api/v1/bookings/{bookingId}/meeting/token -> { server_url, token }
 *   POST /api/v1/bookings/{bookingId}/meeting/end   -> { status: ENDED }
 */
@RestController
public class MeetingController {

    private final MeetingService meetingService;
    private final UserService userService;

    public MeetingController(MeetingService meetingService, UserService userService) {
        this.meetingService = meetingService;
        this.userService = userService;
    }

    @PostMapping("/api/v1/bookings/{bookingId}/meeting/token")
    public LiveKitTokenService.MeetingToken token(@AuthenticationPrincipal UserPrincipal principal,
                                                  @PathVariable UUID bookingId) {
        return meetingService.token(requireUser(principal), bookingId);
    }

    @PostMapping("/api/v1/bookings/{bookingId}/meeting/end")
    public java.util.Map<String, String> end(@AuthenticationPrincipal UserPrincipal principal,
                                             @PathVariable UUID bookingId) {
        return meetingService.end(requireUser(principal), bookingId);
    }

    private User requireUser(UserPrincipal principal) {
        if (principal == null) {
            throw com.okututor.backend.common.error.ApiException.unauthorized("Authentication required");
        }
        return userService.requireById(principal.id());
    }
}
