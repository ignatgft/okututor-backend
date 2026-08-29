package com.okututor.backend.tutors.availability;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.FieldValidationException;
import com.okututor.backend.tutors.availability.dto.AvailabilityRuleRequest;
import com.okututor.backend.tutors.availability.dto.AvailabilityRuleResponse;
import com.okututor.backend.user.User;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AvailabilityRuleService {

    private static final Set<String> WEEKDAYS = Set.of(
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday");

    private final AvailabilityRuleRepository repository;

    public AvailabilityRuleService(AvailabilityRuleRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<AvailabilityRuleResponse> listForTutor(UUID tutorId) {
        return repository.findByTutorIdOrderByWeekdayAscStartTimeAsc(tutorId).stream()
                .map(this::toResponse)
                .toList();
    }

    public AvailabilityRuleResponse add(User tutor, AvailabilityRuleRequest req) {
        if (req.weekday() == null || !WEEKDAYS.contains(req.weekday())) {
            throw new FieldValidationException(java.util.Map.of("weekday", "Weekday must be Monday..Sunday"));
        }
        if (req.startTime() == null || req.endTime() == null || !req.endTime().isAfter(req.startTime())) {
            throw new FieldValidationException(java.util.Map.of("endTime", "endTime must be after startTime"));
        }

        List<AvailabilityRule> existing = repository.findByTutorIdAndWeekday(tutor.getId(), req.weekday());
        for (AvailabilityRule r : existing) {
            if (req.startTime().isBefore(r.getEndTime()) && req.endTime().isAfter(r.getStartTime())) {
                throw new FieldValidationException(java.util.Map.of("startTime",
                        "Time slot overlaps with existing slot %s–%s".formatted(
                                r.getStartTime(), r.getEndTime())));
            }
        }

        AvailabilityRule rule = new AvailabilityRule();
        rule.setTutor(tutor);
        rule.setWeekday(req.weekday());
        rule.setStartTime(req.startTime());
        rule.setEndTime(req.endTime());
        rule.setTimezone(req.timezone() != null ? req.timezone() : "UTC");
        return toResponse(repository.save(rule));
    }

    public void remove(User tutor, UUID ruleId) {
        AvailabilityRule rule = repository.findById(ruleId)
                .filter(r -> r.getTutor().getId().equals(tutor.getId()))
                .orElseThrow(() -> ApiException.notFound("Availability rule not found"));
        repository.delete(rule);
    }

    private AvailabilityRuleResponse toResponse(AvailabilityRule rule) {
        return new AvailabilityRuleResponse(rule.getId(), rule.getWeekday(),
                rule.getStartTime(), rule.getEndTime(), rule.getTimezone());
    }
}