package com.okututor.backend.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.enrollment.EnrollmentRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PersonalizationServiceTest {

    private BookingRepository bookingRepository;
    private EnrollmentRepository enrollmentRepository;
    private PersonalizationService service;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRepository.class);
        enrollmentRepository = mock(EnrollmentRepository.class);
        service = new PersonalizationService(bookingRepository, enrollmentRepository);
    }

    @Test
    void anonymousUser_emptyProfile() {
        assertThat(service.profile(null).isEmpty()).isTrue();
    }

    @Test
    void profile_collectsSubjectsFromBookingsAndEnrollments() {
        UUID userId = UUID.randomUUID();
        when(bookingRepository.findHistorySubjectsByStudent(eq(userId), anyCollection()))
                .thenReturn(java.util.Arrays.asList("Mathematics", null, " "));
        when(bookingRepository.findHistoryTeacherIdsByStudent(eq(userId), anyCollection()))
                .thenReturn(List.of());
        when(enrollmentRepository.findHistorySubjectsByStudent(eq(userId), anyCollection()))
                .thenReturn(List.of("English"));

        PersonalizationService.Profile profile = service.profile(userId);
        assertThat(profile.subjects()).containsExactlyInAnyOrder("mathematics", "english");
    }

    @Test
    void boost_subjectMatchIsStrongest() {
        UUID teacherId = UUID.randomUUID();
        var profile = new PersonalizationService.Profile(
                java.util.Set.of("mathematics"), java.util.Set.of(teacherId));

        assertThat(service.boost(profile, "Mathematics", null)).isEqualTo(1.0);
        assertThat(service.boost(profile, "MATHEMATICS", null)).isEqualTo(1.0);
        assertThat(service.boost(profile, "IT", teacherId)).isEqualTo(0.6);
        assertThat(service.boost(profile, "IT", UUID.randomUUID())).isZero();
    }

    @Test
    void boost_emptyProfileIsZero() {
        assertThat(service.boost(PersonalizationService.Profile.empty(), "Mathematics", UUID.randomUUID()))
                .isZero();
        assertThat(service.boost(null, "Mathematics", UUID.randomUUID())).isZero();
    }

    @Test
    void boost_neverFiltersOnlyBoosts() {
        // кандидат без совпадений получает 0 boost, но не исключается
        var profile = new PersonalizationService.Profile(java.util.Set.of("mathematics"), java.util.Set.of());
        assertThat(service.boost(profile, "Physics", null)).isZero();
    }
}
