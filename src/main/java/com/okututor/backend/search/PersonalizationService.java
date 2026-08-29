package com.okututor.backend.search;

import com.okututor.backend.booking.Booking;
import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.enrollment.Enrollment;
import com.okututor.backend.enrollment.EnrollmentRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Персонализация поиска (этап 5): ТОЛЬКО boost, никогда не фильтр (спека #16).
 * Сигналы: предметы и репетиторы успешных бронирований/зачислений студента.
 * Анонимные пользователи получают пустой профиль — выдача идентична непсонализированной.
 */
public class PersonalizationService {

    /** История пользователя для boost'а: предметы (lowercase) и репетиторы. */
    public record Profile(Set<String> subjects, Set<UUID> teacherIds) {
        public static Profile empty() {
            return new Profile(Set.of(), Set.of());
        }

        public boolean isEmpty() {
            return subjects.isEmpty() && teacherIds.isEmpty();
        }
    }

    private final BookingRepository bookingRepository;
    private final EnrollmentRepository enrollmentRepository;

    public PersonalizationService(BookingRepository bookingRepository,
                                  EnrollmentRepository enrollmentRepository) {
        this.bookingRepository = bookingRepository;
        this.enrollmentRepository = enrollmentRepository;
    }

    public Profile profile(UUID userId) {
        if (userId == null) {
            return Profile.empty();
        }
        List<String> bookingSubjects = bookingRepository.findHistorySubjectsByStudent(
                userId, List.of(Booking.Status.CONFIRMED, Booking.Status.COMPLETED));
        List<UUID> teacherIds = bookingRepository.findHistoryTeacherIdsByStudent(
                userId, List.of(Booking.Status.CONFIRMED, Booking.Status.COMPLETED));
        List<String> enrollmentSubjects = enrollmentRepository.findHistorySubjectsByStudent(
                userId, List.of(Enrollment.Status.ACCEPTED));

        Set<String> subjects = new HashSet<>();
        for (String subject : bookingSubjects) {
            if (subject != null && !subject.isBlank()) {
                subjects.add(subject.toLowerCase(Locale.ROOT));
            }
        }
        for (String subject : enrollmentSubjects) {
            if (subject != null && !subject.isBlank()) {
                subjects.add(subject.toLowerCase(Locale.ROOT));
            }
        }
        return new Profile(Set.copyOf(subjects), Set.copyOf(teacherIds));
    }

    /** Boost 0..1: предмет истории — 1.0, знакомый репетитор — 0.6. */
    public double boost(Profile profile, String candidateSubject, UUID candidateTeacherId) {
        if (profile == null || profile.isEmpty()) {
            return 0;
        }
        if (candidateSubject != null
                && profile.subjects().contains(candidateSubject.toLowerCase(Locale.ROOT))) {
            return 1.0;
        }
        if (candidateTeacherId != null && profile.teacherIds().contains(candidateTeacherId)) {
            return 0.6;
        }
        return 0;
    }
}
