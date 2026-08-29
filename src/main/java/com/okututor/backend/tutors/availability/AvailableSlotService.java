package com.okututor.backend.tutors.availability;

import com.okututor.backend.booking.Booking;
import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.tutors.availability.dto.AvailableSlotResponse;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AvailableSlotService {

    private final AvailabilityRuleRepository ruleRepository;
    private final AvailabilityExceptionRepository exceptionRepository;
    private final BlockedTimeRepository blockedTimeRepository;
    private final TimeOffRepository timeOffRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;

    public AvailableSlotService(AvailabilityRuleRepository ruleRepository,
                                AvailabilityExceptionRepository exceptionRepository,
                                BlockedTimeRepository blockedTimeRepository,
                                TimeOffRepository timeOffRepository,
                                BookingRepository bookingRepository,
                                UserRepository userRepository) {
        this.ruleRepository = ruleRepository;
        this.exceptionRepository = exceptionRepository;
        this.blockedTimeRepository = blockedTimeRepository;
        this.timeOffRepository = timeOffRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
    }

    public List<AvailableSlotResponse> getAvailableSlots(UUID tutorId, Instant from, Instant to, int durationMinutes) {
        User tutor = userRepository.findById(tutorId)
                .orElseThrow(() -> new IllegalArgumentException("Tutor not found"));

        ZoneId zone = tutor.getTimezone() != null ? ZoneId.of(tutor.getTimezone()) : ZoneId.of("UTC");
        LocalDate startDate = from.atZone(zone).toLocalDate();
        LocalDate endDate = to.atZone(zone).toLocalDate();

        // 1. Load weekly rules grouped by weekday
        Map<String, List<AvailabilityRule>> rulesByWeekday = ruleRepository
                .findByTutorIdOrderByWeekdayAscStartTimeAsc(tutorId).stream()
                .collect(Collectors.groupingBy(AvailabilityRule::getWeekday));

        // 2. Load exceptions in range
        Map<LocalDate, AvailabilityException> exceptions = new HashMap<>();
        LocalDate d = startDate;
        while (!d.isAfter(endDate)) {
            LocalDate date = d;
            exceptionRepository.findByTutorIdAndExceptionDate(tutorId, date)
                    .ifPresent(e -> exceptions.put(date, e));
            d = d.plusDays(1);
        }

        // 3. Load blocked times in range
        List<BlockedTime> blockedTimes = blockedTimeRepository.findOverlapping(tutorId, from, to);

        // 4. Load time offs in range
        List<TimeOff> timeOffs = timeOffRepository.findOverlapping(tutorId, startDate, endDate);

        // 5. Load existing bookings in range (CONFIRMED and PENDING)
        List<Booking.Status> activeStatuses = List.of(Booking.Status.CONFIRMED, Booking.Status.PENDING);
        List<Booking> existingBookings = bookingRepository.findByTeacherIdAndStartAtBetween(
                tutorId, activeStatuses, from, to);

        // 6. Compute slots for each day in range
        List<AvailableSlotResponse> slots = new ArrayList<>();
        for (LocalDate current = startDate; !current.isAfter(endDate); current = current.plusDays(1)) {
            String weekdayKey = current.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH);
            LocalDate day = current; // effectively final for lambdas

            // Check time off
            boolean isTimeOff = timeOffs.stream()
                    .anyMatch(t -> !day.isBefore(t.getStartDate()) && !day.isAfter(t.getEndDate()));
            if (isTimeOff) {
                continue;
            }

            // Check exception
            AvailabilityException exception = exceptions.get(day);
            if (exception != null && !exception.isAvailable()) {
                continue;
            }

            // Get base rules for this weekday (or exception override)
            List<AvailabilityRule> baseRules = exception != null && exception.isAvailable()
                    ? List.of()
                    : rulesByWeekday.getOrDefault(weekdayKey, List.of());

            // If exception with custom times, use that instead
            if (exception != null && exception.isAvailable() && exception.getStartTime() != null) {
                // Create a pseudo-rule for the exception
                AvailabilityRule exceptionRule = new AvailabilityRule();
                exceptionRule.setStartTime(exception.getStartTime());
                exceptionRule.setEndTime(exception.getEndTime());
                baseRules = List.of(exceptionRule);
            }

            // Generate slots from rules
            for (AvailabilityRule rule : baseRules) {
                LocalTime slotStart = rule.getStartTime();
                LocalTime slotEnd = rule.getEndTime();

                // Generate slots of requested duration within the rule window
                LocalTime currentTime = slotStart;
                while (currentTime.plusMinutes(durationMinutes).compareTo(slotEnd) <= 0) {
                    Instant startAt = current.atTime(currentTime).atZone(zone).toInstant();
                    Instant endAt = startAt.plusSeconds(durationMinutes * 60L);

                    // Check blocked times
                    boolean isBlocked = blockedTimes.stream()
                            .anyMatch(bt -> startAt.isBefore(bt.getEndAt()) && endAt.isAfter(bt.getStartAt()));
                    if (isBlocked) {
                        currentTime = currentTime.plusMinutes(durationMinutes);
                        continue;
                    }

                    // Check existing bookings
                    boolean isBooked = existingBookings.stream()
                            .anyMatch(b -> startAt.isBefore(b.getEndAt()) && endAt.isAfter(b.getStartAt()));
                    if (isBooked) {
                        currentTime = currentTime.plusMinutes(durationMinutes);
                        continue;
                    }

                    // Check minimum notice (if configured on tutor profile)
                    // TODO: Add tutor settings for minimum notice, max advance, buffer

                    slots.add(new AvailableSlotResponse(startAt, endAt, durationMinutes));
                    currentTime = currentTime.plusMinutes(durationMinutes);
                }
            }

            current = current.plusDays(1);
        }

        return slots;
    }
}