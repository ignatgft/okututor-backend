package com.okututor.backend.tutors;

import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.tutors.dto.TutorApplicationSubmitRequest;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserMapper;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.FieldValidationException;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/tutors")
public class TutorController {

    private final TutorApplicationService applicationService;
    private final AvailabilityService availabilityService;
    private final com.okututor.backend.user.UserService userService;
    private final UserMapper userMapper;

    public TutorController(TutorApplicationService applicationService,
                           AvailabilityService availabilityService,
                           com.okututor.backend.user.UserService userService,
                           UserMapper userMapper) {
        this.applicationService = applicationService;
        this.availabilityService = availabilityService;
        this.userService = userService;
        this.userMapper = userMapper;
    }

    @PostMapping("/applications")
    public TutorApplicationService.ApplicationResponse submitApplication(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody(required = false) @Valid TutorApplicationSubmitRequest body) {
        requireAuth(principal);
        User current = userService.requireById(principal.id());
        if (body == null) {
            // сохраняем прежний контракт: пустое тело → 422 c полем full_name
            throw new FieldValidationException(Map.of("full_name", "Name is required"));
        }
        return applicationService.submit(current, body);
    }

    @GetMapping("/applications/me")
    public TutorApplicationService.ApplicationResponse myApplication(
            @AuthenticationPrincipal UserPrincipal principal) {
        requireAuth(principal);
        return applicationService.mine(principal.id());
    }

    @GetMapping("/{id}")
    public com.okututor.backend.user.dto.PublicUserResponse tutorById(@PathVariable UUID id) {
        User tutor = userService.requireById(id);
        if (tutor.getRole() != com.okututor.backend.user.Role.TUTOR
                && tutor.getRole() != com.okututor.backend.user.Role.ADMIN
                && tutor.getRole() != com.okututor.backend.user.Role.SUPER_ADMIN) {
            throw ApiException.notFound("Tutor not found");
        }
        return userMapper.toPublicResponse(tutor);
    }

    @GetMapping("/{id}/availability")
    public java.util.List<AvailabilityService.SlotResponse> tutorAvailability(@PathVariable UUID id) {
        return availabilityService.forPublicTutor(id);
    }

    // --- слоты доступности живут в AvailabilityController на /api/v1/availability ---

    private static void requireAuth(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("Authentication required");
        }
    }
}
