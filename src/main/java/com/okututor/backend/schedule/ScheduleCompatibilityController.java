package com.okututor.backend.schedule;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Алиасы для соответствия контракту docs/mapping.md §36f–36m:
 * канонические пути фронта ожидают /schedule/applications/... и /schedule/me,
 * тогда как внутренняя реализация исторически жила под /applications/{id}/...
 * Контроллер — тонкая прокси-обёртка без дубля бизнес-логики.
 */
@RestController
public class ScheduleCompatibilityController {

    private final ScheduleService scheduleService;
    private final UserService userService;

    public ScheduleCompatibilityController(ScheduleService scheduleService, UserService userService) {
        this.scheduleService = scheduleService;
        this.userService = userService;
    }

    /** §36f алиас: POST /schedule/applications/{applicationId}/propose */
    @PostMapping("/api/v1/schedule/applications/{id}/propose")
    @PreAuthorize("hasRole('TUTOR')")
    public ScheduleService.ScheduleProposalResponse proposeAlias(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @RequestBody ScheduleService.ProposeRequest request) {
        return scheduleService.propose(currentUser(principal), id, request);
    }

    /** §36g алиас: GET /schedule/applications/{id}/proposals */
    @GetMapping("/api/v1/schedule/applications/{id}/proposals")
    public List<ScheduleService.ScheduleProposalResponse> proposalsAlias(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id) {
        return scheduleService.listProposals(currentUser(principal), id);
    }

    /** §36m алиас с поддержкой обоих имён параметров front/back: from/to vs from_date/to_date */
    @GetMapping("/api/v1/schedule/applications/{id}/available-slots")
    public List<ScheduleService.AvailableSlotResponse> availableSlotsAlias(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID id,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, name = "from_date") String fromDate,
            @RequestParam(required = false, name = "to_date") String toDate,
            @RequestParam(required = false) String timezone) {
        String resolvedFrom = firstNonBlank(from, fromDate);
        String resolvedTo = firstNonBlank(to, toDate);
        if (resolvedFrom == null || resolvedTo == null) {
            throw ApiException.validation("from and to (yyyy-MM-dd) are required");
        }
        return scheduleService.availableSlots(currentUser(principal), id, resolvedFrom, resolvedTo, timezone);
    }

    /** §36k алиас: GET /schedule/me — расписания текущего пользователя */
    @GetMapping("/api/v1/schedule/me")
    public List<ScheduleService.ScheduleResponse> mySchedulesAlias(
            @AuthenticationPrincipal UserPrincipal principal) {
        return scheduleService.mySchedules(currentUser(principal));
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private User currentUser(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        return userService.requireById(principal.id());
    }
}
