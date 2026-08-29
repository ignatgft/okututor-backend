package com.okututor.backend.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RankingServiceTest {

    private final SearchProperties props = new SearchProperties();
    private final PersonalizationService personalization = new PersonalizationService(
            org.mockito.Mockito.mock(com.okututor.backend.booking.BookingRepository.class),
            org.mockito.Mockito.mock(com.okututor.backend.enrollment.EnrollmentRepository.class));
    private final RankingService ranking = new RankingService(props, personalization);

    private CourseSearchProjection candidate(UUID id, double textScore, double trgmScore,
                                             Integer exactMatch, String subject,
                                             Double rating, int reviews) {
        CourseSearchProjection p = mock(CourseSearchProjection.class);
        when(p.getId()).thenReturn(id);
        when(p.getTextScore()).thenReturn(textScore);
        when(p.getTrgmScore()).thenReturn(trgmScore);
        when(p.getExactMatch()).thenReturn(exactMatch);
        when(p.getSubject()).thenReturn(subject);
        when(p.getAverageRating()).thenReturn(rating == null ? null : BigDecimal.valueOf(rating));
        when(p.getReviewsCount()).thenReturn(reviews);
        return p;
    }

    @Test
    void score_isAlwaysWithinZeroOneRange() {
        List<CourseSearchProjection> candidates = List.of(
                candidate(UUID.randomUUID(), 1.0, 1.0, 1, "Mathematics", 5.0, 1000),
                candidate(UUID.randomUUID(), 0.0, 0.0, 0, null, null, 0),
                candidate(UUID.randomUUID(), 0.5, 0.7, 0, "English", 3.5, 4),
                candidate(UUID.randomUUID(), 2.5, -1.0, 0, "IT", 9.9, -5));
        for (CourseSearchProjection c : candidates) {
            assertThat(ranking.score(c, List.of("math"))).isBetween(0.0, 1.0);
        }
    }

    @Test
    void ratingFactor_normalizesZeroToFiveIntoZeroToOne() {
        assertThat(ranking.ratingFactor(candidate(UUID.randomUUID(), 0, 0, 0, null, 5.0, 0)))
                .isCloseTo(1.0, within(1e-9));
        assertThat(ranking.ratingFactor(candidate(UUID.randomUUID(), 0, 0, 0, null, 2.5, 0)))
                .isCloseTo(0.5, within(1e-9));
        assertThat(ranking.ratingFactor(candidate(UUID.randomUUID(), 0, 0, 0, null, null, 0)))
                .isZero();
    }

    @Test
    void reviewFactor_usesSaturatingNormalization() {
        int k = props.getRanking().getReviewSaturation();
        assertThat(ranking.reviewFactor(candidate(UUID.randomUUID(), 0, 0, 0, null, null, 0)))
                .isZero();
        assertThat(ranking.reviewFactor(candidate(UUID.randomUUID(), 0, 0, 0, null, null, k)))
                .isCloseTo(0.5, within(1e-9));
        assertThat(ranking.reviewFactor(candidate(UUID.randomUUID(), 0, 0, 0, null, null, 10_000)))
                .isCloseTo(1.0, within(1e-3));
    }

    @Test
    void subjectFactor_matchesQueryTokensCaseInsensitively() {
        CourseSearchProjection c = candidate(UUID.randomUUID(), 0, 0, 0, "Mathematics", null, 0);
        assertThat(ranking.subjectFactor(c, List.of("математика", "mathematics"))).isEqualTo(1.0);
        assertThat(ranking.subjectFactor(c, List.of("MATHEMATICS"))).isEqualTo(1.0);
        assertThat(ranking.subjectFactor(c, List.of("english"))).isZero();
        assertThat(ranking.subjectFactor(c, List.of())).isZero();
    }

    @Test
    void textFactor_exactMatchWins() {
        CourseSearchProjection exact = candidate(UUID.randomUUID(), 0.1, 0.0, 1, null, null, 0);
        CourseSearchProjection strong = candidate(UUID.randomUUID(), 0.9, 0.9, 0, null, null, 0);
        assertThat(ranking.textFactor(exact, RankingService.RankingContext.of(List.of()))).isEqualTo(1.0);
        assertThat(ranking.score(exact, List.of())).isGreaterThan(ranking.score(strong, List.of()));
    }

    @Test
    void weights_areConfigurable() {
        SearchProperties custom = new SearchProperties();
        custom.getRanking().setTextWeight(1.0);
        custom.getRanking().setSubjectWeight(0);
        custom.getRanking().setRatingWeight(0);
        custom.getRanking().setReviewWeight(0);
        RankingService textOnly = new RankingService(custom, personalization);

        CourseSearchProjection c = candidate(UUID.randomUUID(), 0.42, 0.1, 0, "Math", 5.0, 100);
        assertThat(textOnly.score(c, List.of("math"))).isCloseTo(0.42, within(1e-9));
    }

    @Test
    void rank_sortsByScoreDescWithStableTiebreaker() {
        UUID idA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID idB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        CourseSearchProjection weak = candidate(idA, 0.1, 0, 0, null, 1.0, 0);
        CourseSearchProjection strong = candidate(idB, 0.9, 0, 0, null, 5.0, 50);
        CourseSearchProjection twinA = candidate(idA, 0.5, 0, 0, null, 3.0, 10);
        CourseSearchProjection twinB = candidate(idB, 0.5, 0, 0, null, 3.0, 10);

        List<CourseSearchProjection> ranked = ranking.rank(List.of(weak, strong, twinA, twinB), List.of());

        assertThat(ranked.get(0)).isSameAs(strong);
        assertThat(ranked.get(1)).isSameAs(twinB);
        assertThat(ranked.get(2)).isSameAs(twinA);
        assertThat(ranked.get(3)).isSameAs(weak);
    }

    @Test
    void allWeightsZero_scoreIsZero() {
        SearchProperties custom = new SearchProperties();
        custom.getRanking().setTextWeight(0);
        custom.getRanking().setSubjectWeight(0);
        custom.getRanking().setRatingWeight(0);
        custom.getRanking().setReviewWeight(0);
        RankingService none = new RankingService(custom, personalization);
        assertThat(none.score(candidate(UUID.randomUUID(), 1, 1, 1, "x", 5.0, 10), List.of("x")))
                .isZero();
    }

    @Test
    void availabilityFactor_boostsAvailableTutors() {
        SearchProperties custom = new SearchProperties();
        custom.getRanking().setTextWeight(0);
        custom.getRanking().setSubjectWeight(0);
        custom.getRanking().setRatingWeight(0);
        custom.getRanking().setReviewWeight(0);
        custom.getRanking().setAvailabilityWeight(1.0);
        RankingService availabilityOnly = new RankingService(custom, personalization);

        UUID busyTutor = UUID.randomUUID();
        UUID freeTutor = UUID.randomUUID();
        CourseSearchProjection busy = mock(CourseSearchProjection.class);
        when(busy.getId()).thenReturn(UUID.randomUUID());
        when(busy.getTeacherId()).thenReturn(busyTutor);
        CourseSearchProjection free = mock(CourseSearchProjection.class);
        when(free.getId()).thenReturn(UUID.randomUUID());
        when(free.getTeacherId()).thenReturn(freeTutor);

        var scores = java.util.Map.of(freeTutor, 1.0, busyTutor, 0.0);
        List<CourseSearchProjection> ranked = availabilityOnly.rank(List.of(busy, free),
                new RankingService.RankingContext(List.of(), null, scores,
                        PersonalizationService.Profile.empty()));

        assertThat(ranked.get(0)).isSameAs(free);
    }

    private CourseSearchProjection titled(String title, double textScore) {
        CourseSearchProjection p = mock(CourseSearchProjection.class);
        when(p.getId()).thenReturn(UUID.randomUUID());
        when(p.getTitle()).thenReturn(title);
        when(p.getTextScore()).thenReturn(textScore);
        when(p.getTrgmScore()).thenReturn(0.0);
        when(p.getExactMatch()).thenReturn(0);
        return p;
    }

    @Test
    void textFactor_exactTitleTokenGetsBonus() {
        SearchProperties custom = new SearchProperties();
        custom.getRanking().setTextWeight(1.0);
        custom.getRanking().setSubjectWeight(0);
        custom.getRanking().setRatingWeight(0);
        custom.getRanking().setReviewWeight(0);
        RankingService textOnly = new RankingService(custom, personalization);

        // токен запроса "python" точно совпадает с токеном названия → exact-title-bonus (0.9)
        CourseSearchProjection c = titled("Python Programming", 0.1);
        var ctx = new RankingService.RankingContext(List.of("пайтон", "python"), null,
                java.util.Map.of(), PersonalizationService.Profile.empty());
        assertThat(textOnly.score(c, ctx)).isCloseTo(0.9, within(1e-9));
    }

    @Test
    void textFactor_technologyInTitleGetsBonus() {
        SearchProperties custom = new SearchProperties();
        custom.getRanking().setTextWeight(1.0);
        custom.getRanking().setSubjectWeight(0);
        custom.getRanking().setRatingWeight(0);
        custom.getRanking().setReviewWeight(0);
        RankingService textOnly = new RankingService(custom, personalization);

        // технология PYTHON в названии, но токены запроса не совпадают с токенами названия
        CourseSearchProjection c = titled("Курс Python для новичков", 0.1);
        var ctx = new RankingService.RankingContext(List.of("обучение"), "PYTHON",
                java.util.Map.of(), PersonalizationService.Profile.empty());
        assertThat(textOnly.score(c, ctx)).isCloseTo(0.8, within(1e-9));
    }

    @Test
    void textFactor_exactTokenBeatsTechnologyBonus() {
        SearchProperties custom = new SearchProperties();
        custom.getRanking().setTextWeight(1.0);
        custom.getRanking().setSubjectWeight(0);
        custom.getRanking().setRatingWeight(0);
        custom.getRanking().setReviewWeight(0);
        RankingService textOnly = new RankingService(custom, personalization);

        CourseSearchProjection c = titled("Пайтон для начинающих", 0.0);
        var ctx = new RankingService.RankingContext(List.of("пайтон"), "PYTHON",
                java.util.Map.of(), PersonalizationService.Profile.empty());
        // точный токен «пайтон» в названии → 0.9 (выше tech-бонуса 0.8)
        assertThat(textOnly.score(c, ctx)).isCloseTo(0.9, within(1e-9));
    }

    @Test
    void rank_techTitleCourseAboveGenericProgrammingCourse() {
        SearchProperties custom = new SearchProperties();
        custom.getRanking().setTextWeight(1.0);
        custom.getRanking().setSubjectWeight(0.2);
        custom.getRanking().setRatingWeight(0);
        custom.getRanking().setReviewWeight(0);
        RankingService svc = new RankingService(custom, personalization);

        CourseSearchProjection pythonCourse = mock(CourseSearchProjection.class);
        when(pythonCourse.getId()).thenReturn(UUID.randomUUID());
        when(pythonCourse.getTitle()).thenReturn("Python Programming");
        when(pythonCourse.getSubject()).thenReturn("Programming");
        when(pythonCourse.getTextScore()).thenReturn(0.2);
        when(pythonCourse.getExactMatch()).thenReturn(0);

        CourseSearchProjection genericCourse = mock(CourseSearchProjection.class);
        when(genericCourse.getId()).thenReturn(UUID.randomUUID());
        when(genericCourse.getTitle()).thenReturn("Программирование для детей");
        when(genericCourse.getSubject()).thenReturn("Programming");
        when(genericCourse.getTextScore()).thenReturn(0.2);
        when(genericCourse.getExactMatch()).thenReturn(0);

        var ctx = new RankingService.RankingContext(List.of("пайтон", "python", "питон"), "PYTHON",
                java.util.Map.of(), PersonalizationService.Profile.empty());
        List<CourseSearchProjection> ranked = svc.rank(List.of(genericCourse, pythonCourse), ctx);

        assertThat(ranked.get(0)).isSameAs(pythonCourse);
    }
}
