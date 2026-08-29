package com.okututor.backend.calendar;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserService;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CalendarController {

    private final CalendarService calendarService;
    private final UserService userService;

    public CalendarController(CalendarService calendarService, UserService userService) {
        this.calendarService = calendarService;
        this.userService = userService;
    }

    /**
     * Агрегированный календарь пользователя.
     * from/to — ISO-8601 (UTC) границы [from, to), максимум 90 дней.
     * timezone (IANA) — опционально, для local_start/local_end в ответе.
     */
    @GetMapping("/api/v1/calendar")
    public List<CalendarService.CalendarItem> calendar(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) String timezone) {
        if (principal == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        User viewer = userService.requireById(principal.id());
        return calendarService.calendar(viewer, instant(from, "from"), instant(to, "to"), timezone);
    }

    private static Instant instant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            throw new com.okututor.backend.common.error.FieldValidationException(
                    java.util.Map.of(field, "Expected ISO-8601 instant like 2026-09-01T00:00:00Z"));
        }
    }
}
