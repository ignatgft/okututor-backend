package com.okututor.backend.tutors;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.FieldValidationException;
import com.okututor.backend.notification.NotificationService;
import com.okututor.backend.notification.NotificationType;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserRepository;
import com.okututor.backend.user.Role;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TutorApplicationService {

    public record ApplicationResponse(
            UUID id,
            String status,
            String rejection_reason,
            Instant created_at,
            Instant updated_at
    ) {
        public static ApplicationResponse notRequested() {
            return new ApplicationResponse(null, "NOT_REQUESTED", null, null, null);
        }
    }

    public record AdminApplicationRow(
            UUID id,
            UUID user_id,
            String email,
            String full_name,
            String phone,
            String location,
            Integer experience_years,
            String experience_description,
            String education,
            String subjects,
            String languages,
            String bio,
            String id_document_name,
            String status,
            String rejection_reason,
            Instant created_at
    ) {}

    /** полная заявка для админа: все поля submit + user email/avatar/created_at + статус. */
    public record AdminTutorApplicationDetail(
            UUID id,
            UUID user_id,
            String email,
            String full_name,
            String avatar_url,
            String phone,
            String location,
            Integer experience_years,
            String experience_description,
            String education,
            String subjects,
            String languages,
            String bio,
            String id_document_name,
            String status,
            String rejection_reason,
            Instant created_at,
            Instant updated_at,
            Instant user_created_at
    ) {}

    private final TutorApplicationRepository repository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public TutorApplicationService(TutorApplicationRepository repository, UserRepository userRepository,
                                   NotificationService notificationService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public ApplicationResponse submit(User current, com.okututor.backend.tutors.dto.TutorApplicationSubmitRequest request) {
        String fullName = request == null ? null : request.full_name();
        if (fullName == null || fullName.isBlank()) {
            throw new FieldValidationException(Map.of("full_name", "Name is required"));
        }

        TutorApplication application = repository.findByUserId(current.getId())
                .orElseGet(() -> {
                    TutorApplication fresh = new TutorApplication();
                    fresh.setUser(current);
                    return fresh;
                });

        if (application.getStatus() == TutorApplication.Status.APPROVED) {
            throw ApiException.conflict("Your tutor application has already been approved");
        }

        application.setFullName(fullName.trim());
        application.setPhone(request.phone());
        application.setLocation(request.location());
        application.setExperienceYears(request.experience_years());
        application.setExperienceDescription(request.experience_description());
        application.setEducation(request.education());
        application.setSubjects(request.subjects());
        application.setLanguages(request.languages());
        application.setBio(request.bio());
        application.setIdDocumentName(request.id_document_name());
        if (application.getStatus() == TutorApplication.Status.REJECTED) {
            application.setStatus(TutorApplication.Status.PENDING); // повторная отправка разрешена
            application.setRejectionReason(null);
        }
        repository.save(application);
        return toResponse(application);
    }

    @Transactional(readOnly = true)
    public ApplicationResponse mine(UUID userId) {
        return repository.findByUserId(userId).map(this::toResponse)
                .orElse(ApplicationResponse.notRequested());
    }

    @Transactional
    public ApplicationResponse approve(UUID userId) {
        TutorApplication application = repository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Application not found"));
        if (application.getStatus() == TutorApplication.Status.APPROVED) {
            throw ApiException.conflict("Application is already approved");
        }
        application.setStatus(TutorApplication.Status.APPROVED);
        application.setRejectionReason(null);

        User applicant = application.getUser();
        applicant.setRole(Role.TUTOR);
        applicant.setVerified(true);
        if (application.getExperienceYears() != null) {
            applicant.setExperienceYears(application.getExperienceYears());
        }
        if (application.getEducation() != null && !application.getEducation().isBlank()) {
            applicant.setEducation(application.getEducation());
        }
        userRepository.save(applicant);
        ApplicationResponse response = toResponse(repository.save(application));
        notificationService.notify(applicant.getId(),
                "Заявка «стать тьютором» одобрена — теперь вы можете создавать курсы",
                NotificationType.TUTOR_APPLICATION_APPROVED,
                "/tutor/dashboard");
        return response;
    }

    @Transactional
    public ApplicationResponse reject(UUID userId, String reason) {
        TutorApplication application = repository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Application not found"));
        if (application.getStatus() == TutorApplication.Status.REJECTED) {
            throw ApiException.conflict("Application is already rejected");
        }
        application.setStatus(TutorApplication.Status.REJECTED);
        application.setRejectionReason(reason);
        ApplicationResponse response = toResponse(repository.save(application));
        User applicant = application.getUser();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reason", reason);
        notificationService.notify(applicant.getId(),
                reason == null || reason.isBlank()
                        ? "Заявка «стать тьютором» отклонена"
                        : "Заявка «стать тьютором» отклонена: " + reason,
                NotificationType.TUTOR_APPLICATION_REJECTED,
                "/tutor/application",
                payload);
        return response;
    }

    /** полная заявка по {id} (суррогатному PK) для админ-карточки. 404, если нет. */
    @Transactional(readOnly = true)
    public AdminTutorApplicationDetail detail(UUID id) {
        TutorApplication a = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Application not found"));
        User user = a.getUser();
        return new AdminTutorApplicationDetail(
                a.getId(),
                user != null ? user.getId() : null,
                user != null ? user.getEmail() : null,
                a.getFullName(),
                user != null ? user.getAvatarUrl() : null,
                a.getPhone(),
                a.getLocation(),
                a.getExperienceYears(),
                a.getExperienceDescription(),
                a.getEducation(),
                a.getSubjects(),
                a.getLanguages(),
                a.getBio(),
                a.getIdDocumentName(),
                a.getStatus().name(),
                a.getRejectionReason(),
                a.getCreatedAt(),
                a.getUpdatedAt(),
                user != null ? user.getCreatedAt() : null);
    }

    @Transactional(readOnly = true)
    public Page<AdminApplicationRow> list(String status, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        TutorApplication.Status parsed = parseStatus(status);
        return (parsed == null
                ? repository.findAll(pageable)
                : repository.findByStatusOrderByCreatedAtDesc(parsed, pageable))
                .map(this::toAdminRow);
    }

    static TutorApplication.Status parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return TutorApplication.Status.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("Unknown status: " + raw);
        }
    }

    private AdminApplicationRow toAdminRow(TutorApplication a) {
        User user = a.getUser();
        return new AdminApplicationRow(
                a.getId(), user != null ? user.getId() : null, user != null ? user.getEmail() : null,
                a.getFullName(), a.getPhone(), a.getLocation(), a.getExperienceYears(),
                a.getExperienceDescription(), a.getEducation(), a.getSubjects(), a.getLanguages(),
                a.getBio(), a.getIdDocumentName(), a.getStatus().name(), a.getRejectionReason(),
                a.getCreatedAt());
    }

    private ApplicationResponse toResponse(TutorApplication application) {
        return new ApplicationResponse(
                application.getUser() != null ? application.getUser().getId() : application.getId(),
                application.getStatus().name(),
                application.getRejectionReason(),
                application.getCreatedAt(),
                application.getUpdatedAt());
    }
}
