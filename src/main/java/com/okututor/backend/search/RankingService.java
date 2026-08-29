package com.okututor.backend.search;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ранжирование отфильтрованных кандидатов (спека #14, #15).
 *
 * <p>Каждый фактор нормализуется в 0..1 ДО умножения на вес; итоговый score —
 * взвешенная сумма, нормированная на сумму активных весов (вес &gt; 0).
 * Веса конфигурируются в application.yml ({@code search.ranking.*}).
 * Ranking работает только по пулу кандидатов, уже прошедшему hard-фильтры SQL,
 * и никогда их не отменяет. Совпадение языка запроса и названия НЕ бустится —
 * бустится только фактическое совпадение токенов/технологии.
 */
public class RankingService {

    private final SearchProperties props;
    private final PersonalizationService personalizationService;

    public RankingService(SearchProperties props, PersonalizationService personalizationService) {
        this.props = props;
        this.personalizationService = personalizationService;
    }

    /** Контекст ранжирования: токены матчинга, технология, доступность, персонализация. */
    public record RankingContext(List<String> matchTokens,
                                 String technology,
                                 Map<UUID, Double> availability,
                                 PersonalizationService.Profile profile) {

        public static RankingContext of(List<String> matchTokens) {
            return new RankingContext(matchTokens, null, Map.of(), PersonalizationService.Profile.empty());
        }
    }

    public List<CourseSearchProjection> rank(List<CourseSearchProjection> candidates,
                                             List<String> queryTokens) {
        return rank(candidates, RankingContext.of(queryTokens));
    }

    /** Сортировка кандидатов по убыванию score; tiebreaker — id DESC (стабильный порядок). */
    public List<CourseSearchProjection> rank(List<CourseSearchProjection> candidates,
                                             RankingContext context) {
        return candidates.stream()
                .sorted(Comparator
                        .comparingDouble((CourseSearchProjection c) -> score(c, context)).reversed()
                        .thenComparing(CourseSearchProjection::getId, Comparator.reverseOrder()))
                .toList();
    }

    public double score(CourseSearchProjection candidate, List<String> queryTokens) {
        return score(candidate, RankingContext.of(queryTokens));
    }

    public double score(CourseSearchProjection candidate, RankingContext context) {
        SearchProperties.Ranking r = props.getRanking();
        double weightedSum = 0;
        double weightTotal = 0;

        if (r.getTextWeight() > 0) {
            weightedSum += r.getTextWeight() * textFactor(candidate, context);
            weightTotal += r.getTextWeight();
        }
        if (r.getSubjectWeight() > 0) {
            weightedSum += r.getSubjectWeight() * subjectFactor(candidate, context.matchTokens());
            weightTotal += r.getSubjectWeight();
        }
        if (r.getRatingWeight() > 0) {
            weightedSum += r.getRatingWeight() * ratingFactor(candidate);
            weightTotal += r.getRatingWeight();
        }
        if (r.getReviewWeight() > 0) {
            weightedSum += r.getReviewWeight() * reviewFactor(candidate);
            weightTotal += r.getReviewWeight();
        }
        if (r.getAvailabilityWeight() > 0) {
            weightedSum += r.getAvailabilityWeight() * availabilityFactor(candidate, context.availability());
            weightTotal += r.getAvailabilityWeight();
        }
        if (r.getPersonalizationWeight() > 0) {
            weightedSum += r.getPersonalizationWeight() * personalizationFactor(candidate, context.profile());
            weightTotal += r.getPersonalizationWeight();
        }
        return weightTotal == 0 ? 0 : weightedSum / weightTotal;
    }

    /**
     * Текстовая релевантность:
     * exact title (весь запрос) &gt; точный токен запроса в названии (exact-title-bonus)
     * &gt; технология в названии (technology-title-bonus) &gt; FTS/trgm score.
     */
    double textFactor(CourseSearchProjection c, RankingContext context) {
        if (c.getExactMatch() != null && c.getExactMatch() == 1) {
            return 1.0;
        }
        double base = clamp01(Math.max(c.getTextScore(), c.getTrgmScore()));
        double bonus = 0;

        String title = c.getTitle() == null ? "" : c.getTitle().toLowerCase(Locale.ROOT);
        if (!title.isEmpty() && context != null && context.matchTokens() != null) {
            Set<String> titleTokens = titleTokens(title);
            boolean exactToken = context.matchTokens().stream()
                    .filter(t -> t != null && !t.isBlank())
                    .anyMatch(t -> titleTokens.contains(t.toLowerCase(Locale.ROOT)));
            if (exactToken) {
                bonus = Math.max(bonus, props.getRanking().getExactTitleBonus());
            }
        }
        if (!title.isEmpty() && context != null && context.technology() != null
                && !context.technology().isBlank()
                && title.contains(context.technology().toLowerCase(Locale.ROOT))) {
            bonus = Math.max(bonus, props.getRanking().getTechnologyTitleBonus());
        }
        return clamp01(Math.max(base, bonus));
    }

    private static Set<String> titleTokens(String lowerTitle) {
        Set<String> tokens = new HashSet<>();
        for (String token : lowerTitle.split("[^\\p{L}\\p{N}+#]+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /** Совпадение предмета курса с токенами запроса/синонимами/алиасами технологии. */
    double subjectFactor(CourseSearchProjection c, List<String> matchTokens) {
        if (c.getSubject() == null || matchTokens == null || matchTokens.isEmpty()) {
            return 0;
        }
        String subject = c.getSubject().toLowerCase(Locale.ROOT);
        boolean match = matchTokens.stream()
                .filter(t -> t != null && !t.isBlank())
                .anyMatch(t -> subject.contains(t.toLowerCase(Locale.ROOT)));
        return match ? 1.0 : 0;
    }

    /** Рейтинг 0..5 → 0..1; курсы без рейтинга — 0 (не завышаем и не занижаем). */
    double ratingFactor(CourseSearchProjection c) {
        if (c.getAverageRating() == null) {
            return 0;
        }
        return clamp01(c.getAverageRating().doubleValue() / 5.0);
    }

    /** Количество отзывов с насыщением reviews/(reviews+k): 10 отзывов ≈ 0.5. */
    double reviewFactor(CourseSearchProjection c) {
        int reviews = c.getReviewsCount() == null ? 0 : Math.max(0, c.getReviewsCount());
        int k = Math.max(1, props.getRanking().getReviewSaturation());
        return clamp01((double) reviews / (reviews + k));
    }

    /** Этап 4: эффективная доступность репетитора (slots минус active bookings), 0..1. */
    double availabilityFactor(CourseSearchProjection c, Map<UUID, Double> availabilityScores) {
        if (availabilityScores == null || c.getTeacherId() == null) {
            return 0;
        }
        Double score = availabilityScores.get(c.getTeacherId());
        return score == null ? 0 : clamp01(score);
    }

    /** Этап 5: boost по истории бронирований/зачислений (только boost, не фильтр). */
    double personalizationFactor(CourseSearchProjection c, PersonalizationService.Profile profile) {
        return clamp01(personalizationService.boost(profile, c.getSubject(), c.getTeacherId()));
    }

    static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
