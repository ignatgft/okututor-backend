package com.okututor.backend.tutors;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.FieldValidationException;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserRepository;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AvailabilityService {

    public record SlotResponse(UUID id, String weekday, String start_time, String end_time) {}

    private static final Set<String> WEEKDAYS = Set.of(
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday");

    private final AvailabilitySlotRepository repository;
    private final UserRepository userRepository;

    public AvailabilityService(AvailabilitySlotRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<SlotResponse> listForTutor(UUID tutorId) {
        return repository.findByTutorIdOrderByWeekdayAscStartTimeAsc(tutorId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public SlotResponse add(User tutor, Map<String, Object> payload) {
        String weekday = str(payload.get("weekday"));
        LocalTime start = parseTime(payload.get("start_time"), "start_time");
        LocalTime end = parseTime(payload.get("end_time"), "end_time");

        if (weekday == null || !WEEKDAYS.contains(weekday)) {
            throw new FieldValidationException(Map.of("weekday", "Weekday must be Monday..Sunday"));
        }
        if (start == null || end == null || !end.isAfter(start)) {
            throw new FieldValidationException(Map.of("end_time", "end_time must be after start_time"));
        }

        // проверка пересечения с существующими слотами в тот же день недели
        List<AvailabilitySlot> existing = repository.findByTutorIdAndWeekday(tutor.getId(), weekday);
        for (AvailabilitySlot s : existing) {
            if (start.isBefore(s.getEndTime()) && end.isAfter(s.getStartTime())) {
                throw new FieldValidationException(Map.of("start_time",
                        "Time slot overlaps with existing slot %s–%s".formatted(
                                s.getStartTime(), s.getEndTime())));
            }
        }

        AvailabilitySlot slot = new AvailabilitySlot();
        slot.setTutor(tutor);
        slot.setWeekday(weekday);
        slot.setStartTime(start);
        slot.setEndTime(end);
        return toResponse(repository.save(slot));
    }

    @Transactional
    public void remove(User tutor, UUID slotId) {
        AvailabilitySlot slot = repository.findByIdAndTutorId(slotId, tutor.getId())
                .orElseThrow(() -> ApiException.notFound("Availability slot not found"));
        repository.delete(slot);
    }

    /** публичный вид расписания репетитора. */
    @Transactional(readOnly = true)
    public List<SlotResponse> forPublicTutor(UUID tutorId) {
        User tutor = userRepository.findById(tutorId)
                .filter(u -> u.getRole() == com.okututor.backend.user.Role.TUTOR)
                .orElseThrow(() -> ApiException.notFound("Tutor not found"));
        return listForTutor(tutor.getId());
    }

    private SlotResponse toResponse(AvailabilitySlot slot) {
        return new SlotResponse(slot.getId(), slot.getWeekday(),
                slot.getStartTime().toString(), slot.getEndTime().toString());
    }

    private static LocalTime parseTime(Object value, String field) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value.toString());
        } catch (DateTimeParseException e) {
            throw new FieldValidationException(Map.of(field, "Expected HH:mm time"));
        }
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }
}
