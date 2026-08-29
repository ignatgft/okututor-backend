package com.okututor.backend.search;

import com.okututor.backend.search.normalizer.SearchQueryNormalizer;
import com.okututor.backend.search.understanding.RuleBasedQueryParser;
import com.okututor.backend.search.understanding.StructuredQuery;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Объяснение причин показа курса (этап 4): детерминированные человекочитаемые
 * коды, выводятся из фактических сигналов, а не из LLM.
 */
public class ExplanationService {

    private final RuleBasedQueryParser ruleParser;

    public ExplanationService(RuleBasedQueryParser ruleParser) {
        this.ruleParser = ruleParser;
    }

    /**
     * @param subjectAliases     алиасы effective-предмета (явный фильтр или извлечённый)
     * @param availability       score доступности репетитора (может быть null)
     * @param personalization    персональный boost кандидата (может быть null)
     */
    public List<String> explain(CourseSearchProjection candidate,
                                StructuredQuery understood,
                                SearchQueryNormalizer.NormalizedQuery normalized,
                                List<String> subjectAliases,
                                Double availability,
                                Double personalization) {
        List<String> reasons = new ArrayList<>();

        if (candidate.getExactMatch() != null && candidate.getExactMatch() == 1) {
            reasons.add("exact_title_match");
        } else if (candidate.getTextScore() > 0) {
            reasons.add("matched_text");
        } else if (candidate.getTrgmScore() > 0) {
            reasons.add("matched_similar_spelling");
        }

        if (matchesSubject(candidate, subjectAliases)) {
            reasons.add("matched_subject");
        }
        if (matchesSynonym(candidate, normalized)) {
            reasons.add("matched_synonym");
        }
        if (understood != null && understood.goal() != null && matchesGoal(candidate, understood)) {
            reasons.add("matched_goal");
        }
        BigDecimal price = candidate.getPricePerHour();
        BigDecimal budget = understood == null ? null : understood.priceMax();
        if (price != null && budget != null && price.compareTo(budget) <= 0) {
            reasons.add("price_within_budget");
        }
        if (candidate.getAverageRating() != null
                && candidate.getAverageRating().doubleValue() >= 4.0) {
            reasons.add("high_rating");
        }
        if (availability != null && availability > 0) {
            reasons.add("available_this_week");
        }
        if (personalization != null && personalization > 0) {
            reasons.add("personalized_for_you");
        }
        return reasons;
    }

    private boolean matchesSubject(CourseSearchProjection candidate, List<String> subjectAliases) {
        if (candidate.getSubject() == null || subjectAliases == null || subjectAliases.isEmpty()) {
            return false;
        }
        String subject = candidate.getSubject().toLowerCase(Locale.ROOT);
        return subjectAliases.stream()
                .filter(a -> a != null && !a.isBlank())
                .anyMatch(a -> containsWord(subject, a.toLowerCase(Locale.ROOT)));
    }

    /** Синоним-матчинг: токен из расширения (не из исходного запроса) найден в полях курса. */
    private boolean matchesSynonym(CourseSearchProjection candidate,
                                   SearchQueryNormalizer.NormalizedQuery normalized) {
        if (normalized == null) {
            return false;
        }
        List<String> originals = normalized.originalTokens();
        String haystack = String.join(" ",
                nullToEmpty(candidate.getTitle()),
                nullToEmpty(candidate.getSubject()),
                nullToEmpty(candidate.getCategory())).toLowerCase(Locale.ROOT);
        return normalized.expandedTokens().stream()
                .filter(t -> t != null && !t.isBlank())
                .filter(t -> !originals.contains(t))
                .anyMatch(t -> containsWord(haystack, t.toLowerCase(Locale.ROOT)));
    }

    private boolean matchesGoal(CourseSearchProjection candidate, StructuredQuery understood) {
        String haystack = String.join(" ",
                nullToEmpty(candidate.getTitle()),
                nullToEmpty(candidate.getDescription())).toLowerCase(Locale.ROOT);
        // goal — канонический ключ (ORT); матчим его ключевые слова ("орт", "ort", ...)
        return ruleParser.goalKeywords(understood.goal()).stream()
                .filter(k -> k != null && !k.isBlank())
                .anyMatch(k -> containsWord(haystack, k.toLowerCase(Locale.ROOT)));
    }

    /** Поиск токена как целого слова (границы — не буквы/цифры), без regex-зависимости. */
    static boolean containsWord(String haystackLower, String tokenLower) {
        if (haystackLower == null || tokenLower == null || tokenLower.isEmpty()) {
            return false;
        }
        int idx = 0;
        while ((idx = haystackLower.indexOf(tokenLower, idx)) >= 0) {
            boolean leftOk = idx == 0 || !Character.isLetterOrDigit(haystackLower.charAt(idx - 1));
            int end = idx + tokenLower.length();
            boolean rightOk = end >= haystackLower.length()
                    || !Character.isLetterOrDigit(haystackLower.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            idx++;
        }
        return false;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
