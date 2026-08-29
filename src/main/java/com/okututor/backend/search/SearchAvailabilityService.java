package com.okututor.backend.search;

import com.okututor.backend.booking.Booking;
import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.tutors.AvailabilitySlot;
import com.okututor.backend.tutors.AvailabilitySlotRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Эффективная доступность репетитора для поиска (этап 4):
 * weekly availability_slots минус активные bookings (PENDING/CONFIRMED).
 * Score = доля свободных дней в ближайшие 7 дней, 0..1.
 * Считается батчем для всех кандидатов — без N+1.
 */
public class SearchAvailabilityService {

    private static final int HORIZON_DAYS = 7;

    private final AvailabilitySlotRepository slotRepository;
    private final BookingRepository bookingRepository;

    public SearchAvailabilityService(AvailabilitySlotRepository slotRepository,
                                     BookingRepository bookingRepository) {
        this.slotRepository = slotRepository;
        this.bookingRepository = bookingRepository;
    }

    public Map<UUID, Double> availabilityScores(Collection<UUID> teacherIds) {
        if (teacherIds == null || teacherIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> ids = new HashSet<>(teacherIds);
        List<AvailabilitySlot> slots = slotRepository.findByTutorIdIn(ids);
        if (slots.isEmpty()) {
            return Map.of();
        }

        Instant now = Instant.now();
        Instant horizon = now.plus(HORIZON_DAYS + 1, ChronoUnit.DAYS);
        List<Booking> bookings = bookingRepository.findActiveByTeacherIds(
                ids, List.of(Booking.Status.PENDING, Booking.Status.CONFIRMED), now, horizon);

        Map<UUID, List<AvailabilitySlot>> slotsByTutor = slots.stream()
                .collect(Collectors.groupingBy(s -> s.getTutor().getId()));
        Map<UUID, List<Booking>> bookingsByTutor = bookings.stream()
                .collect(Collectors.groupingBy(Booking::getTeacherId));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Map<UUID, Double> result = new HashMap<>();
        for (UUID id : ids) {
            List<AvailabilitySlot> tutorSlots = slotsByTutor.getOrDefault(id, List.of());
            if (tutorSlots.isEmpty()) {
                result.put(id, 0.0);
                continue;
            }
            List<Booking> tutorBookings = bookingsByTutor.getOrDefault(id, List.of());
            int openDays = 0;
            for (int d = 0; d < HORIZON_DAYS; d++) {
                LocalDate date = today.plusDays(d);
                String weekday = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                boolean hasFreeSlot = tutorSlots.stream()
                        .filter(s -> weekday.equals(s.getWeekday()))
                        .anyMatch(slot -> !slotOverlapsBooking(slot, date, tutorBookings));
                if (hasFreeSlot) {
                    openDays++;
                }
            }
            result.put(id, openDays / (double) HORIZON_DAYS);
        }
        return result;
    }

    private boolean slotOverlapsBooking(AvailabilitySlot slot, LocalDate date, List<Booking> bookings) {
        Instant slotStart = date.atTime(slot.getStartTime()).toInstant(ZoneOffset.UTC);
        Instant slotEnd = date.atTime(slot.getEndTime()).toInstant(ZoneOffset.UTC);
        return bookings.stream()
                .anyMatch(b -> b.getStartAt().isBefore(slotEnd) && b.getEndAt().isAfter(slotStart));
    }
}
