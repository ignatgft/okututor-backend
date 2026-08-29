package com.okututor.backend.tutors.availability;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.FieldValidationException;
import com.okututor.backend.tutors.availability.dto.AvailabilityExceptionRequest;
import com.okututor.backend.tutors.availability.dto.AvailabilityExceptionResponse;
import com.okututor.backend.user.User;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AvailabilityExceptionService {

    private final AvailabilityExceptionRepository repository;

    public AvailabilityExceptionService(AvailabilityExceptionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<AvailabilityExceptionResponse> listForTutor(UUID tutorId) {
        return repository.findByTutorId(tutorId).stream()
                .map(this::toResponse)
                .toList();
    }

    public AvailabilityExceptionResponse upsert(User tutor, AvailabilityExceptionRequest req) {
        if (req.date() == null) {
            throw new FieldValidationException(java.util.Map.of("date", "date is required"));
        }
        if (req.available() == null) {
            throw new FieldValidationException(java.util.Map.of("available", "available is required"));
        }
        if (Boolean.TRUE.equals(req.available()) && (req.startTime() == null || req.endTime() == null)) {
            throw new FieldValidationException(java.util.Map.of("startTime", "startTime and endTime required when available=true"));
        }
        if (Boolean.TRUE.equals(req.available()) && !req.endTime().isAfter(req.startTime())) {
            throw new FieldValidationException(java.util.Map.of("endTime", "endTime must be after startTime"));
        }

        AvailabilityException exception = repository.findByTutorIdAndExceptionDate(tutor.getId(), req.date())
                .orElse(new AvailabilityException());
        exception.setTutor(tutor);
        exception.setExceptionDate(req.date());
        exception.setAvailable(req.available());
        exception.setStartTime(req.startTime());
        exception.setEndTime(req.endTime());
        return toResponse(repository.save(exception));
    }

    public void remove(User tutor, UUID exceptionId) {
        AvailabilityException exception = repository.findById(exceptionId)
                .filter(e -> e.getTutor().getId().equals(tutor.getId()))
                .orElseThrow(() -> ApiException.notFound("Availability exception not found"));
        repository.delete(exception);
    }

    private AvailabilityExceptionResponse toResponse(AvailabilityException e) {
        return new AvailabilityExceptionResponse(e.getId(), e.getExceptionDate(), e.isAvailable(),
                e.getStartTime(), e.getEndTime());
    }
}