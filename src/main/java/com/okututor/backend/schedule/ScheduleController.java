package com.okututor.backend.schedule;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.lesson.LessonService;
import com.okututor.backend.security.UserPrincipal;
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
 * Расписание занятий и подтверждение предложения (spec §32 §34):
 * студент принимает / отклоняет / контредит предложение тьютора.
 */
@RestController
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final UserService userService;

    public ScheduleController(ScheduleService scheduleService, UserService userService) {
        this.scheduleService = scheduleService;
        this.userService = userService;
    }

    /** расписания текущего пользователя (студента или тьютора). */
    @GetMapping("/api/v1/schedule")
    public List<ScheduleService.ScheduleResponse> mySchedules(@AuthenticationPrincipal UserPrincipal principal) {
        return scheduleService.mySchedules(currentUser(principal));
    }

    @GetMapping("/api/v1/schedule/{id}")
    public ScheduleService.ScheduleResponse byId(@AuthenticationPrincipal UserPrincipal principal,
                                                 @PathVariable UUID id) {
        return scheduleService.getById(currentUser(principal), id);
    }

    /** конкретные занятия конкретного расписания. */
    @GetMapping("/api/v1/schedule/{id}/lessons")
    public List<LessonService.LessonResponse> lessons(@AuthenticationPrincipal UserPrincipal principal,
                                                      @PathVariable UUID id,
                                                      @RequestParam(defaultValue = "20") int limit) {
        return scheduleService.lessons(currentUser(principal), id).stream().limit(Math.min(Math.max(limit, 1), 500)).toList();
    }

    @GetMapping("/api/v1/schedule/proposals/{id}")
    public ScheduleService.ScheduleProposalResponse proposal(@AuthenticationPrincipal UserPrincipal principal,
                                                             @PathVariable UUID id) {
        return scheduleService.getProposal(currentUser(principal), id);
    }

    @PostMapping("/api/v1/schedule/proposals/{id}/accept")
    @PreAuthorize("hasRole('STUDENT')")
    public ScheduleService.AcceptResponse accept(@AuthenticationPrincipal UserPrincipal principal,
                                                 @PathVariable UUID id) {
        return scheduleService.accept(currentUser(principal), id);
    }

    @PostMapping("/api/v1/schedule/proposals/{id}/reject")
    @PreAuthorize("hasRole('STUDENT')")
    public ScheduleService.ScheduleProposalResponse reject(@AuthenticationPrincipal UserPrincipal principal,
                                                           @PathVariable UUID id) {
        return scheduleService.reject(currentUser(principal), id);
    }

    /** встречный вариант расписания от студента. */
    @PostMapping("/api/v1/schedule/proposals/{id}/counter")
    @PreAuthorize("hasRole('STUDENT')")
    public ScheduleService.ScheduleProposalResponse counter(@AuthenticationPrincipal UserPrincipal principal,
                                                            @PathVariable UUID id,
                                                            @RequestBody ScheduleService.ProposeRequest request) {
        return scheduleService.counter(currentUser(principal), id, request);
    }

    private com.okututor.backend.user.User currentUser(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        return userService.requireById(principal.id());
    }
}