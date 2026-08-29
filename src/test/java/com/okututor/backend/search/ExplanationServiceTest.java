package com.okututor.backend.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.okututor.backend.search.normalizer.SearchQueryNormalizer;
import com.okututor.backend.search.understanding.RuleBasedQueryParser;
import com.okututor.backend.search.understanding.StructuredQuery;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExplanationServiceTest {

    private final ExplanationService service = new ExplanationService(new RuleBasedQueryParser());

    private CourseSearchProjection candidate(String title, String subject, String description,
                                             Double textScore, Double trgmScore, Integer exactMatch,
                                             Double rating, BigDecimal price) {
        CourseSearchProjection p = mock(CourseSearchProjection.class);
        when(p.getTitle()).thenReturn(title);
        when(p.getSubject()).thenReturn(subject);
        when(p.getDescription()).thenReturn(description);
        when(p.getCategory()).thenReturn(null);
        when(p.getTextScore()).thenReturn(textScore == null ? 0 : textScore);
        when(p.getTrgmScore()).thenReturn(trgmScore == null ? 0 : trgmScore);
        when(p.getExactMatch()).thenReturn(exactMatch);
        when(p.getAverageRating()).thenReturn(rating == null ? null : BigDecimal.valueOf(rating));
        when(p.getPricePerHour()).thenReturn(price);
        return p;
    }

    private StructuredQuery understood(BigDecimal priceMax, String goal) {
        return new StructuredQuery(StructuredQuery.Intent.FIND_COURSE, null, null, goal,
                null, null, priceMax, null, null, null, false);
    }

    @Test
    void textMatchReasons() {
        var exact = candidate("Java", "IT", null, 0.2, 0.0, 1, null, null);
        assertThat(service.explain(exact, understood(null, null), null, List.of(), null, null))
                .contains("exact_title_match");

        var fts = candidate("Java Basics", "IT", null, 0.5, 0.0, 0, null, null);
        assertThat(service.explain(fts, understood(null, null), null, List.of(), null, null))
                .contains("matched_text");

        var typo = candidate("Java", "IT", null, 0.0, 0.6, 0, null, null);
        assertThat(service.explain(typo, understood(null, null), null, List.of(), null, null))
                .contains("matched_similar_spelling");
    }

    @Test
    void subjectAndBudgetAndRatingReasons() {
        var c = candidate("Algebra", "Mathematics", null, 0.4, 0.0, 0, 4.5, new BigDecimal("500"));
        List<String> reasons = service.explain(c,
                understood(new BigDecimal("1000"), null), null,
                List.of("математика", "mathematics"), null, null);
        assertThat(reasons).contains("matched_subject", "price_within_budget", "high_rating");
    }

    @Test
    void overBudget_notIncluded() {
        var c = candidate("Algebra", "Mathematics", null, 0.4, 0.0, 0, 3.0, new BigDecimal("1500"));
        List<String> reasons = service.explain(c,
                understood(new BigDecimal("1000"), null), null, List.of("mathematics"), null, null);
        assertThat(reasons).doesNotContain("price_within_budget", "high_rating");
    }

    @Test
    void synonymReason_whenExpandedTokenMatches() {
        var c = candidate("Java для новичков", "IT", null, 0.3, 0.0, 0, null, null);
        var normalized = new SearchQueryNormalizer.NormalizedQuery(
                "java:*", "джава", List.of("джава", "java"), false, List.of("джава"));
        assertThat(service.explain(c, understood(null, null), normalized, List.of(), null, null))
                .contains("matched_synonym");
    }

    @Test
    void goalReason_whenGoalKeywordInText() {
        var c = candidate("Подготовка к ОРТ", "Mathematics", "курс подготовки к орт", 0.3, 0.0, 0, null, null);
        assertThat(service.explain(c, understood(null, "ORT"), null, List.of(), null, null))
                .contains("matched_goal");
    }

    @Test
    void availabilityReason() {
        var c = candidate("Java", "IT", null, 0.3, 0.0, 0, null, null);
        assertThat(service.explain(c, understood(null, null), null, List.of(), 0.5, null))
                .contains("available_this_week");
        assertThat(service.explain(c, understood(null, null), null, List.of(), 0.0, null))
                .doesNotContain("available_this_week");
    }
}
