package com.okututor.backend.stats;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatsController {

    private final StatsService statsService;
    private final UserService userService;

    public StatsController(StatsService statsService, UserService userService) {
        this.statsService = statsService;
        this.userService = userService;
    }

    /** прогресс студента (/student/progress). Пустая статистика — нули, не 404. */
    @GetMapping("/api/v1/students/me/stats")
    public StatsService.StudentStats studentStats(@AuthenticationPrincipal UserPrincipal principal) {
        return statsService.studentStats(currentUser(principal));
    }

    /** прогресс тьютора (/tutor/progress). */
    @GetMapping("/api/v1/tutors/me/stats")
    @PreAuthorize("hasAnyRole('TUTOR','ADMIN','SUPER_ADMIN')")
    public StatsService.TutorStats tutorStats(@AuthenticationPrincipal UserPrincipal principal) {
        return statsService.tutorStats(currentUser(principal));
    }

    private User currentUser(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        return userService.requireById(principal.id());
    }
}
