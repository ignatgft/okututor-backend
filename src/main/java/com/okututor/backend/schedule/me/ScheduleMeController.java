package com.okututor.backend.schedule.me;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.lesson.dto.LessonStatusLabelService;
import com.okututor.backend.schedule.me.dto.ScheduleMeDtos;
import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;

@RestController
@RequestMapping({"/api/v1/schedule/me", "/schedule/me"})
public class ScheduleMeController {

    private final ScheduleMeService scheduleMeService;
    private final UserService userService;
    private final LessonStatusLabelService labelService;

    public ScheduleMeController(ScheduleMeService scheduleMeService,
                                UserService userService,
                                LessonStatusLabelService labelService) {
        this.scheduleMeService = scheduleMeService;
        this.userService = userService;
        this.labelService = labelService;
    }

    @GetMapping("/next")
    public ScheduleMeDtos.NextLessonResponse next(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage,
            @RequestHeader(value = "X-Time-Zone", required = false) String tzHeader,
            @RequestParam(value = "timezone", required = false) String tzParam) {
        User user = currentUser(principal);
        ZoneId zone = resolveZone(tzParam != null ? tzParam : tzHeader, user);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return scheduleMeService.getNext(user, zone, locale);
    }

    @GetMapping("/actions")
    public ScheduleMeDtos.ActionsResponse actions(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User user = currentUser(principal);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return scheduleMeService.getActions(user, locale);
    }

    @GetMapping("/day")
    public ScheduleMeDtos.DayResponse day(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam String date,
            @RequestParam(value = "timezone", required = false) String tzParam,
            @RequestHeader(value = "X-Time-Zone", required = false) String tzHeader,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User user = currentUser(principal);
        ZoneId zone = resolveZone(tzParam != null ? tzParam : tzHeader, user);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        LocalDate d;
        try {
            d = LocalDate.parse(date);
        } catch (Exception e) {
            throw ApiException.validation("date must be yyyy-MM-dd");
        }
        return scheduleMeService.getDay(user, d, zone, locale);
    }

    @GetMapping("/week")
    public ScheduleMeDtos.WeekResponse week(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "start_date", required = false) String startDate2,
            @RequestParam(value = "start", required = false) String startAlias,
            @RequestParam(value = "date", required = false) String dateAlias,
            @RequestParam(value = "timezone", required = false) String tzParam,
            @RequestHeader(value = "X-Time-Zone", required = false) String tzHeader,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        User user = currentUser(principal);
        String raw = startDate != null ? startDate : (startDate2 != null ? startDate2 : (startAlias != null ? startAlias : dateAlias));
        if (raw == null || raw.isBlank()) {
            throw ApiException.validation("startDate is required (yyyy-MM-dd)");
        }
        LocalDate d;
        try {
            d = LocalDate.parse(raw);
        } catch (Exception e) {
            throw ApiException.validation("startDate must be yyyy-MM-dd");
        }
        ZoneId zone = resolveZone(tzParam != null ? tzParam : tzHeader, user);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return scheduleMeService.getWeek(user, d, zone, locale);
    }

    @GetMapping("/month")
    public ScheduleMeDtos.MonthResponse month(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam int year,
            @RequestParam int month,
            @RequestParam(value = "timezone", required = false) String tzParam,
            @RequestHeader(value = "X-Time-Zone", required = false) String tzHeader,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        if (month < 1 || month > 12) {
            throw ApiException.validation("month must be 1..12");
        }
        if (year < 1970 || year > 2100) {
            throw ApiException.validation("year out of range");
        }
        User user = currentUser(principal);
        ZoneId zone = resolveZone(tzParam != null ? tzParam : tzHeader, user);
        Locale locale = labelService.parseAcceptLanguage(acceptLanguage);
        return scheduleMeService.getMonth(user, year, month, zone, locale);
    }

    private User currentUser(UserPrincipal principal) {
        if (principal == null) throw ApiException.unauthorized("Authentication required");
        return userService.requireById(principal.id());
    }

    private ZoneId resolveZone(String tz, User user) {
        String candidate = tz;
        if (candidate == null || candidate.isBlank()) {
            candidate = user.getTimezone();
        }
        if (candidate == null || candidate.isBlank()) candidate = "UTC";
        try {
            return ZoneId.of(candidate);
        } catch (Exception e) {
            return ZoneOffset.UTC;
        }
    }
}
