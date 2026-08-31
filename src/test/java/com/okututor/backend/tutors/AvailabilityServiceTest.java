package com.okututor.backend.tutors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.FieldValidationException;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AvailabilityServiceTest {

    private AvailabilitySlotRepository repository;
    private AvailabilityService service;
    private User tutor;

    @BeforeEach
    void setUp() {
        repository = mock(AvailabilitySlotRepository.class);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new AvailabilityService(repository, mock(com.okututor.backend.user.UserRepository.class),
                mock(com.okututor.backend.admin.AuditLogService.class));
        tutor = new User();
        tutor.setId(UUID.randomUUID());
        tutor.setRole(Role.TUTOR);
    }

    @Test
    void addRejectsUnknownWeekday() {
        assertThatThrownBy(() -> service.add(tutor,
                Map.of("weekday", "Funday", "start_time", "18:00", "end_time", "22:00")))
                .isInstanceOf(FieldValidationException.class)
                .extracting(e -> ((FieldValidationException) e).getFieldErrors())
                .satisfies(errors -> assertThat((Map<String, String>) errors).containsKey("weekday"));
    }

    @Test
    void addRejectsEndBeforeStart() {
        assertThatThrownBy(() -> service.add(tutor,
                Map.of("weekday", "Monday", "start_time", "18:00", "end_time", "08:00")))
                .isInstanceOf(FieldValidationException.class)
                .extracting(e -> ((FieldValidationException) e).getFieldErrors())
                .satisfies(errors -> assertThat((Map<String, String>) errors).containsKey("end_time"));
    }

    @Test
    void addAcceptsValidSlotAndFormatsTimes() {
        AvailabilityService.SlotResponse response = service.add(tutor,
                Map.of("weekday", "Monday", "start_time", "18:00", "end_time", "22:00"));

        assertThat(response.weekday()).isEqualTo("Monday");
        assertThat(response.start_time()).isEqualTo(LocalTime.of(18, 0).toString());
        assertThat(response.end_time()).isEqualTo("22:00");
    }

    @Test
    void addDefaultsTimezoneToUtc() {
        AvailabilityService.SlotResponse response = service.add(tutor,
                Map.of("weekday", "Monday", "start_time", "18:00", "end_time", "22:00"));
        assertThat(response.timezone()).isEqualTo("UTC");
    }

    @Test
    void addAcceptsValidTimezone() {
        AvailabilityService.SlotResponse response = service.add(tutor,
                Map.of("weekday", "Tuesday", "start_time", "09:00", "end_time", "11:00",
                        "timezone", "Asia/Bishkek"));
        assertThat(response.timezone()).isEqualTo("Asia/Bishkek");
    }

    @Test
    void addRejectsUnknownTimezone() {
        assertThatThrownBy(() -> service.add(tutor,
                Map.of("weekday", "Monday", "start_time", "18:00", "end_time", "22:00",
                        "timezone", "NotA/Zone")))
                .isInstanceOf(FieldValidationException.class)
                .extracting(e -> ((FieldValidationException) e).getFieldErrors())
                .satisfies(errors -> assertThat((Map<String, String>) errors).containsKey("timezone"));
    }

    @Test
    void findCommonSlotsIntersectsTutorAndStudentWindows() {
        UUID tutorId = tutor.getId();
        UUID studentId = UUID.randomUUID();

        AvailabilitySlot tutorSlot = new AvailabilitySlot();
        tutorSlot.setWeekday("Tuesday");
        tutorSlot.setStartTime(LocalTime.of(10, 0));
        tutorSlot.setEndTime(LocalTime.of(14, 0));

        AvailabilitySlot studentSlot = new AvailabilitySlot();
        studentSlot.setWeekday("Tuesday");
        studentSlot.setStartTime(LocalTime.of(12, 0));
        studentSlot.setEndTime(LocalTime.of(16, 0));

        when(repository.findByTutorIdAndWeekday(tutorId, "Tuesday")).thenReturn(java.util.List.of(tutorSlot));
        when(repository.findByTutorIdAndWeekday(studentId, "Tuesday")).thenReturn(java.util.List.of(studentSlot));

        LocalDate tuesday = LocalDate.of(2026, 9, 1); // Tuesday
        var slots = service.findCommonSlots(tutorId, studentId, tuesday);

        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).start_local()).isEqualTo("12:00");
        assertThat(slots.get(0).end_local()).isEqualTo("14:00");
    }

    @Test
    void findCommonSlotsReturnsEmptyWhenNoOverlap() {
        UUID tutorId = tutor.getId();
        UUID studentId = UUID.randomUUID();

        AvailabilitySlot tutorSlot = new AvailabilitySlot();
        tutorSlot.setWeekday("Tuesday");
        tutorSlot.setStartTime(LocalTime.of(9, 0));
        tutorSlot.setEndTime(LocalTime.of(10, 0));

        AvailabilitySlot studentSlot = new AvailabilitySlot();
        studentSlot.setWeekday("Tuesday");
        studentSlot.setStartTime(LocalTime.of(11, 0));
        studentSlot.setEndTime(LocalTime.of(12, 0));

        when(repository.findByTutorIdAndWeekday(tutorId, "Tuesday")).thenReturn(java.util.List.of(tutorSlot));
        when(repository.findByTutorIdAndWeekday(studentId, "Tuesday")).thenReturn(java.util.List.of(studentSlot));

        assertThat(service.findCommonSlots(tutorId, studentId, LocalDate.of(2026, 9, 1))).isEmpty();
    }

    @Test
    void removeOnlyOwnSlots() {
        UUID slotId = UUID.randomUUID();
        AvailabilitySlot foreign = new AvailabilitySlot();
        User other = new User();
        other.setId(UUID.randomUUID());
        foreign.setTutor(other);

        when(repository.findByIdAndTutorId(slotId, tutor.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove(tutor, slotId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void addRejectsOverlapWithExistingSlot() {
        // существующий слот Monday 10:00–12:00
        AvailabilitySlot existing = new AvailabilitySlot();
        existing.setWeekday("Monday");
        existing.setStartTime(LocalTime.of(10, 0));
        existing.setEndTime(LocalTime.of(12, 0));
        when(repository.findByTutorIdAndWeekday(tutor.getId(), "Monday"))
                .thenReturn(java.util.List.of(existing));

        // новый слот Monday 11:00–13:00 пересекается
        assertThatThrownBy(() -> service.add(tutor,
                Map.of("weekday", "Monday", "start_time", "11:00", "end_time", "13:00")))
                .isInstanceOf(FieldValidationException.class)
                .extracting(e -> ((FieldValidationException) e).getFieldErrors())
                .satisfies(errors -> assertThat((Map<String, String>) errors).containsKey("start_time"));
    }

    @Test
    void addAcceptsNonOverlappingSlotOnSameDay() {
        // существующий слот Monday 10:00–12:00
        AvailabilitySlot existing = new AvailabilitySlot();
        existing.setWeekday("Monday");
        existing.setStartTime(LocalTime.of(10, 0));
        existing.setEndTime(LocalTime.of(12, 0));
        when(repository.findByTutorIdAndWeekday(tutor.getId(), "Monday"))
                .thenReturn(java.util.List.of(existing));

        // новый слот Monday 13:00–15:00 не пересекается
        AvailabilityService.SlotResponse response = service.add(tutor,
                Map.of("weekday", "Monday", "start_time", "13:00", "end_time", "15:00"));

        assertThat(response.start_time()).isEqualTo("13:00");
    }
}
