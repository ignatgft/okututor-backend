package com.okututor.backend.tutors.availability;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.tutors.AvailabilityService;
import com.okututor.backend.tutors.availability.dto.AvailabilityExceptionRequest;
import com.okututor.backend.tutors.availability.dto.AvailabilityExceptionResponse;
import com.okututor.backend.tutors.availability.dto.AvailabilityRuleRequest;
import com.okututor.backend.tutors.availability.dto.AvailabilityRuleResponse;
import com.okututor.backend.tutors.availability.dto.BlockedTimeRequest;
import com.okututor.backend.tutors.availability.dto.BlockedTimeResponse;
import com.okututor.backend.tutors.availability.dto.TimeOffRequest;
import com.okututor.backend.tutors.availability.dto.TimeOffResponse;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasRole('TUTOR')")
public class AvailabilityController {

    private final AvailabilityService availabilityService;
    private final UserService userService;
    private final AvailabilityRuleService ruleService;
    private final AvailabilityExceptionService exceptionService;
    private final BlockedTimeService blockedTimeService;
    private final TimeOffService timeOffService;

    public AvailabilityController(AvailabilityService availabilityService,
                                  UserService userService,
                                  AvailabilityRuleService ruleService,
                                  AvailabilityExceptionService exceptionService,
                                  BlockedTimeService blockedTimeService,
                                  TimeOffService timeOffService) {
        this.availabilityService = availabilityService;
        this.userService = userService;
        this.ruleService = ruleService;
        this.exceptionService = exceptionService;
        this.blockedTimeService = blockedTimeService;
        this.timeOffService = timeOffService;
    }

    @GetMapping("/api/v1/availability")
    public List<AvailabilityService.SlotResponse> myAvailability(@AuthenticationPrincipal UserPrincipal principal) {
        requireAuth(principal);
        return availabilityService.listForTutor(principal.id());
    }

    @PostMapping("/api/v1/availability")
    public AvailabilityService.SlotResponse add(@AuthenticationPrincipal UserPrincipal principal,
                                                @RequestBody(required = false) Map<String, Object> payload) {
        requireAuth(principal);
        User tutor = userService.requireById(principal.id());
        return availabilityService.add(tutor, payload == null ? Map.of() : payload);
    }

    @DeleteMapping("/api/v1/availability/{id}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        requireAuth(principal);
        availabilityService.remove(userService.requireById(principal.id()), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/tutors/me/availability/rules")
    public ResponseEntity<List<AvailabilityRuleResponse>> listRules(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(ruleService.listForTutor(principal.id()));
    }

    @PostMapping("/api/v1/tutors/me/availability/rules")
    public ResponseEntity<AvailabilityRuleResponse> addRule(@AuthenticationPrincipal UserPrincipal principal,
                                                            @Valid @RequestBody AvailabilityRuleRequest req) {
        User tutor = new User();
        tutor.setId(principal.id());
        return ResponseEntity.ok(ruleService.add(tutor, req));
    }

    @DeleteMapping("/api/v1/tutors/me/availability/rules/{ruleId}")
    public ResponseEntity<Void> removeRule(@AuthenticationPrincipal UserPrincipal principal,
                                           @PathVariable UUID ruleId) {
        User tutor = new User();
        tutor.setId(principal.id());
        ruleService.remove(tutor, ruleId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/tutors/me/availability/exceptions")
    public ResponseEntity<List<AvailabilityExceptionResponse>> listExceptions(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(exceptionService.listForTutor(principal.id()));
    }

    @PostMapping("/api/v1/tutors/me/availability/exceptions")
    public ResponseEntity<AvailabilityExceptionResponse> upsertException(@AuthenticationPrincipal UserPrincipal principal,
                                                                         @Valid @RequestBody AvailabilityExceptionRequest req) {
        User tutor = new User();
        tutor.setId(principal.id());
        return ResponseEntity.ok(exceptionService.upsert(tutor, req));
    }

    @DeleteMapping("/api/v1/tutors/me/availability/exceptions/{exceptionId}")
    public ResponseEntity<Void> removeException(@AuthenticationPrincipal UserPrincipal principal,
                                                @PathVariable UUID exceptionId) {
        User tutor = new User();
        tutor.setId(principal.id());
        exceptionService.remove(tutor, exceptionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/tutors/me/availability/blocked")
    public ResponseEntity<List<BlockedTimeResponse>> listBlocked(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(blockedTimeService.listForTutor(principal.id()));
    }

    @PostMapping("/api/v1/tutors/me/availability/blocked")
    public ResponseEntity<BlockedTimeResponse> addBlocked(@AuthenticationPrincipal UserPrincipal principal,
                                                          @Valid @RequestBody BlockedTimeRequest req) {
        User tutor = new User();
        tutor.setId(principal.id());
        return ResponseEntity.ok(blockedTimeService.add(tutor, req));
    }

    @DeleteMapping("/api/v1/tutors/me/availability/blocked/{blockedId}")
    public ResponseEntity<Void> removeBlocked(@AuthenticationPrincipal UserPrincipal principal,
                                              @PathVariable UUID blockedId) {
        User tutor = new User();
        tutor.setId(principal.id());
        blockedTimeService.remove(tutor, blockedId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/tutors/me/availability/time-off")
    public ResponseEntity<List<TimeOffResponse>> listTimeOff(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(timeOffService.listForTutor(principal.id()));
    }

    @PostMapping("/api/v1/tutors/me/availability/time-off")
    public ResponseEntity<TimeOffResponse> addTimeOff(@AuthenticationPrincipal UserPrincipal principal,
                                                      @Valid @RequestBody TimeOffRequest req) {
        User tutor = new User();
        tutor.setId(principal.id());
        return ResponseEntity.ok(timeOffService.add(tutor, req));
    }

    @DeleteMapping("/api/v1/tutors/me/availability/time-off/{timeOffId}")
    public ResponseEntity<Void> removeTimeOff(@AuthenticationPrincipal UserPrincipal principal,
                                              @PathVariable UUID timeOffId) {
        User tutor = new User();
        tutor.setId(principal.id());
        timeOffService.remove(tutor, timeOffId);
        return ResponseEntity.noContent().build();
    }

    private static void requireAuth(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("Authentication required");
        }
    }
}
