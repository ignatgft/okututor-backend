package com.okututor.backend.admin;

import com.okututor.backend.common.error.ApiException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** очередь жалоб (наполняется seed-ом, пока нет пользовательского эндпоинта). */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class AdminReportController {

    public record ReportResponse(
            UUID id,
            String reporter_id,
            String target_type,
            String target_id,
            String reason,
            String status,
            Instant created_at,
            Instant updated_at
    ) {}

    private final ReportRepository repository;

    public AdminReportController(ReportRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/v1/admin/reports")
    public Page<ReportResponse> reports(@RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size) {
        Page<Report> result = (status == null || status.isBlank())
                ? repository.findAll(PageRequest.of(page, Math.min(size, 100)))
                : repository.findByStatusOrderByCreatedAtDesc(parseStatus(status),
                        PageRequest.of(page, Math.min(size, 100)));
        return result.map(this::toResponse);
    }

    /** PUT согласно admin.api.js updateReport. */
    @PutMapping("/api/v1/admin/reports/{id}")
    public ReportResponse update(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        Report report = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Report not found"));
        if (body != null && body.get("status") != null) {
            report.setStatus(parseStatus(body.get("status")));
        }
        return toResponse(repository.save(report));
    }

    private static Report.Status parseStatus(String raw) {
        try {
            return Report.Status.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new com.okututor.backend.common.error.FieldValidationException(Map.of(
                    "status", "One of OPEN/IN_REVIEW/RESOLVED/DISMISSED"));
        }
    }

    private ReportResponse toResponse(Report r) {
        return new ReportResponse(r.getId(),
                r.getReporterId() == null ? null : r.getReporterId().toString(),
                r.getTargetType(), r.getTargetId(), r.getReason(),
                r.getStatus().name(), r.getCreatedAt(), r.getUpdatedAt());
    }
}
