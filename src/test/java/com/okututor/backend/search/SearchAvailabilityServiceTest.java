package com.okututor.backend.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.okututor.backend.booking.Booking;
import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.tutors.AvailabilitySlot;
import com.okututor.backend.tutors.AvailabilitySlotRepository;
import com.okututor.backend.user.User;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SearchAvailabilityServiceTest {

    private AvailabilitySlotRepository slotRepository;
    private BookingRepository bookingRepository;
    private SearchAvailabilityService service;

    @BeforeEach
    void setUp() {
        slotRepository = mock(AvailabilitySlotRepository.class);
        bookingRepository = mock(BookingRepository.class);
        service = new SearchAvailabilityService(slotRepository, bookingRepository);
    }

    private User tutor(UUID id) {
        User u = mock(User.class);
        when(u.getId()).thenReturn(id);
        return u;
    }

    private AvailabilitySlot slot(User tutor, String weekday, LocalTime start, LocalTime end) {
        AvailabilitySlot s = new AvailabilitySlot();
        s.setTutor(tutor);
        s.setWeekday(weekday);
        s.setStartTime(start);
        s.setEndTime(end);
        return s;
    }

    private Booking booking(UUID teacherId, Instant start, Instant end, Booking.Status status) {
        Booking b = mock(Booking.class);
        User teacher = mock(User.class);
        when(teacher.getId()).thenReturn(teacherId);
        when(b.getTeacher()).thenReturn(teacher);
        when(b.getTeacherId()).thenReturn(teacherId);
        when(b.getStartAt()).thenReturn(start);
        when(b.getEndAt()).thenReturn(end);
        when(b.getStatus()).thenReturn(status);
        return b;
    }

    /** слот на каждый день недели → все 7 дней свободны при отсутствии бронирований. */
    @Test
    void allWeekFree_scoreIsOne() {
        UUID tutorId = UUID.randomUUID();
        User t = tutor(tutorId);
        List<AvailabilitySlot> slots = List.of("Monday", "Tuesday", "Wednesday", "Thursday",
                        "Friday", "Saturday", "Sunday").stream()
                .map(day -> slot(t, day, LocalTime.of(10, 0), LocalTime.of(11, 0)))
                .toList();
        when(slotRepository.findByTutorIdIn(anyCollection())).thenReturn(slots);
        when(bookingRepository.findActiveByTeacherIds(anyCollection(), anyCollection(), any(), any()))
                .thenReturn(List.of());

        Map<UUID, Double> scores = service.availabilityScores(List.of(tutorId));
        assertThat(scores.get(tutorId)).isEqualTo(1.0);
    }

    @Test
    void noSlots_scoreIsZeroAndBookingsNotQueried() {
        UUID tutorId = UUID.randomUUID();
        when(slotRepository.findByTutorIdIn(anyCollection())).thenReturn(List.of());

        Map<UUID, Double> scores = service.availabilityScores(List.of(tutorId));
        assertThat(scores).isEmpty();
        verify(bookingRepository, never()).findActiveByTeacherIds(anyCollection(), anyCollection(), any(), any());
    }

    @Test
    void bookingOverlappingSlot_reducesScore() {
        UUID tutorId = UUID.randomUUID();
        User t = tutor(tutorId);
        // слот только на завтрашний день недели
        LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        String weekday = tomorrow.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        Instant bookingStart = tomorrow.atTime(LocalTime.of(10, 30)).toInstant(ZoneOffset.UTC);
        Booking activeBooking = booking(tutorId, bookingStart,
                bookingStart.plus(1, ChronoUnit.HOURS), Booking.Status.CONFIRMED);
        when(slotRepository.findByTutorIdIn(anyCollection()))
                .thenReturn(List.of(slot(t, weekday, LocalTime.of(10, 0), LocalTime.of(12, 0))));
        when(bookingRepository.findActiveByTeacherIds(anyCollection(), anyCollection(), any(), any()))
                .thenReturn(List.of(activeBooking));

        Map<UUID, Double> scores = service.availabilityScores(List.of(tutorId));
        assertThat(scores.get(tutorId)).isEqualTo(0.0);
    }

    @Test
    void cancelledBooking_doesNotReduceScore() {
        UUID tutorId = UUID.randomUUID();
        User t = tutor(tutorId);
        LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        String weekday = tomorrow.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        when(slotRepository.findByTutorIdIn(anyCollection()))
                .thenReturn(List.of(slot(t, weekday, LocalTime.of(10, 0), LocalTime.of(12, 0))));
        // репозиторий возвращает только PENDING/CONFIRMED — отменённые не попадают
        when(bookingRepository.findActiveByTeacherIds(anyCollection(), anyCollection(), any(), any()))
                .thenReturn(List.of());

        Map<UUID, Double> scores = service.availabilityScores(List.of(tutorId));
        assertThat(scores.get(tutorId)).isCloseTo(1.0 / 7.0, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    void emptyInput_returnsEmptyMap() {
        assertThat(service.availabilityScores(List.of())).isEmpty();
        assertThat(service.availabilityScores(null)).isEmpty();
    }
}
