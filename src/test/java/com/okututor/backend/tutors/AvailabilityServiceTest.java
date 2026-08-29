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
        service = new AvailabilityService(repository, mock(com.okututor.backend.user.UserRepository.class));
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
