package com.okututor.backend.tutors.availability;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.FieldValidationException;
import com.okututor.backend.tutors.availability.dto.TimeOffRequest;
import com.okututor.backend.tutors.availability.dto.TimeOffResponse;
import com.okututor.backend.user.User;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TimeOffService {

    private final TimeOffRepository repository;

    public TimeOffService(TimeOffRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<TimeOffResponse> listForTutor(UUID tutorId) {
        return repository.findByTutorId(tutorId).stream()
                .map(this::toResponse)
                .toList();
    }

    public TimeOffResponse add(User tutor, TimeOffRequest req) {
        if (req.startDate() == null || req.endDate() == null || req.endDate().isBefore(req.startDate())) {
            throw new FieldValidationException(java.util.Map.of("endDate", "endDate must be on or after startDate"));
        }

        TimeOff timeOff = new TimeOff();
        timeOff.setTutor(tutor);
        timeOff.setStartDate(req.startDate());
        timeOff.setEndDate(req.endDate());
        timeOff.setReason(req.reason());
        return toResponse(repository.save(timeOff));
    }

    public void remove(User tutor, UUID timeOffId) {
        TimeOff timeOff = repository.findById(timeOffId)
                .filter(t -> t.getTutor().getId().equals(tutor.getId()))
                .orElseThrow(() -> ApiException.notFound("Time off not found"));
        repository.delete(timeOff);
    }

    private TimeOffResponse toResponse(TimeOff t) {
        return new TimeOffResponse(t.getId(), t.getStartDate(), t.getEndDate(), t.getReason());
    }
}