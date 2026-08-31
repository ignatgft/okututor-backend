package com.okututor.backend.tutors;

import com.okututor.backend.admin.AuditEntry;
import com.okututor.backend.admin.AuditLogService;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.FieldValidationException;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AvailabilityService {

    public record SlotResponse(UUID id, String weekday, String start_time, String end_time, String timezone) {}

    private static final Set<String> WEEKDAYS = Set.of(
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday");

    private final AvailabilitySlotRepository repository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public AvailabilityService(AvailabilitySlotRepository repository, UserRepository userRepository,
                               AuditLogService auditLogService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
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
        String timezone = parseTimezone(payload.get("timezone"));

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
        slot.setTimezone(timezone);
        AvailabilitySlot saved = repository.save(slot);
        auditLogService.logSync(new AuditEntry(tutor.getId(), "AVAILABILITY_CHANGED", "AVAILABILITY",
                saved.getId() != null ? saved.getId().toString() : null,
                "added " + weekday + " " + start + "-" + end + " in " + timezone));
        return toResponse(saved);
    }

    @Transactional
    public void remove(User tutor, UUID slotId) {
        AvailabilitySlot slot = repository.findByIdAndTutorId(slotId, tutor.getId())
                .orElseThrow(() -> ApiException.notFound("Availability slot not found"));
        auditLogService.logSync(new AuditEntry(tutor.getId(), "AVAILABILITY_CHANGED", "AVAILABILITY",
                slotId.toString(),
                "removed " + slot.getWeekday() + " " + slot.getStartTime() + "-" + slot.getEndTime()
                        + " in " + (slot.getTimezone() == null ? "UTC" : slot.getTimezone())));
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

    /** общее окно доступности tutorId и studentId на конкретную дату (в UTC). */
    @Transactional(readOnly = true)
    public List<CommonSlot> findCommonSlots(UUID tutorId, UUID studentId, LocalDate date) {
        String weekday = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        List<AvailabilitySlot> tutorSlots = repository.findByTutorIdAndWeekday(tutorId, weekday);
        List<AvailabilitySlot> studentSlots = repository.findByTutorIdAndWeekday(studentId, weekday);
        ZoneId tutorZone = zoneOf(tutorSlots);
        ZoneId studentZone = zoneOf(studentSlots);
        List<CommonSlot> result = new ArrayList<>();
        for (AvailabilitySlot t : tutorSlots) {
            for (AvailabilitySlot s : studentSlots) {
                // локальные интервалы пересечения (в минутах от полуночи)
                int start = Math.max(timeMin(t.getStartTime()), timeMin(s.getStartTime()));
                int end = Math.min(timeMin(t.getEndTime()), timeMin(s.getEndTime()));
                if (start < end) {
                    Instant startUtc = date.atTime(minOfDay(start)).atZone(zoneFor(t.getTimezone(), tutorZone))
                            .toInstant();
                    Instant endUtc = date.atTime(minOfDay(end)).atZone(zoneFor(s.getTimezone(), studentZone))
                            .toInstant();
                    result.add(new CommonSlot(date.toString(),
                            startUtc.toString(), endUtc.toString(),
                            minOfDay(start).toString(), minOfDay(end).toString()));
                }
            }
        }
        result.sort(Comparator.comparing(CommonSlot::start_time));
        return result;
    }

    public record CommonSlot(String date, String start_time, String end_time,
                             String start_local, String end_local) {}

    private static ZoneId zoneFor(String timezone, ZoneId fallback) {
        if (timezone == null || timezone.isBlank()) {
            return fallback;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (java.time.DateTimeException e) {
            return fallback;
        }
    }

    private static ZoneId zoneOf(List<AvailabilitySlot> slots) {
        for (AvailabilitySlot s : slots) {
            if (s.getTimezone() != null && !s.getTimezone().isBlank()) {
                try {
                    return ZoneId.of(s.getTimezone().trim());
                } catch (java.time.DateTimeException ignored) {
                    // fallthrough
                }
            }
        }
        return ZoneOffset.UTC;
    }

    private static int timeMin(LocalTime t) {
        return t.toSecondOfDay() / 60;
    }

    private static LocalTime minOfDay(int minutes) {
        return LocalTime.ofSecondOfDay(minutes * 60L);
    }

    private SlotResponse toResponse(AvailabilitySlot slot) {
        return new SlotResponse(slot.getId(), slot.getWeekday(),
                slot.getStartTime().toString(), slot.getEndTime().toString(),
                slot.getTimezone() != null ? slot.getTimezone() : "UTC");
    }

    private static String parseTimezone(Object value) {
        if (value == null || value.toString().isBlank()) {
            return "UTC";
        }
        String raw = value.toString().trim();
        try {
            java.time.ZoneId.of(raw);
        } catch (java.time.DateTimeException e) {
            throw new FieldValidationException(Map.of("timezone", "Unknown IANA timezone: " + raw));
        }
        return raw;
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
