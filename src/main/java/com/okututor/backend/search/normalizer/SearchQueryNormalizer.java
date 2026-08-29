package com.okututor.backend.search.normalizer;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public class SearchQueryNormalizer {

    private static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_TOKEN_COUNT = 20;
    private static final int MIN_FUZZY_LENGTH = 3;

    private static final Set<String> STOP_WORDS = Set.of(
            "и", "или", "но", "а", "в", "на", "с", "по", "для", "от", "до", "из", "к", "о", "об",
            "the", "and", "or", "but", "in", "on", "with", "for", "to", "from", "by", "of", "a", "an"
    );

    private final KeyboardLayoutNormalizer keyboardNormalizer;
    private final SynonymExpander synonymExpander;

    public SearchQueryNormalizer(KeyboardLayoutNormalizer keyboardNormalizer,
                                 SynonymExpander synonymExpander) {
        this.keyboardNormalizer = keyboardNormalizer;
        this.synonymExpander = synonymExpander;
    }

    public NormalizedQuery normalize(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return NormalizedQuery.empty();
        }

        String trimmed = rawQuery.trim();
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            trimmed = trimmed.substring(0, MAX_QUERY_LENGTH);
        }

        String lowercased = trimmed.toLowerCase(Locale.ROOT);

        String unicodeNormalized = java.text.Normalizer
                .normalize(lowercased, java.text.Normalizer.Form.NFKC);

        String keyboardCorrected = keyboardNormalizer.correctLayout(unicodeNormalized);

        List<String> tokens = tokenize(keyboardCorrected);
        tokens = removeStopWords(tokens);
        if (tokens.size() > MAX_TOKEN_COUNT) {
            tokens = tokens.subList(0, MAX_TOKEN_COUNT);
        }

        List<String> expandedTokens = synonymExpander.expand(tokens, keyboardCorrected);

        String ftsQuery = buildFtsQuery(tokens);
        String fuzzyQuery = buildFuzzyQuery(tokens);

        boolean shouldUseFuzzy = tokens.stream()
                .anyMatch(t -> t.length() >= MIN_FUZZY_LENGTH);

        return new NormalizedQuery(
                ftsQuery,
                fuzzyQuery,
                expandedTokens,
                shouldUseFuzzy,
                tokens);
    }

    private List<String> tokenize(String query) {
        return java.util.Arrays.stream(query.split("\\s+"))
                .filter(t -> !t.isBlank())
                .map(String::trim)
                .collect(Collectors.toList());
    }

    private List<String> removeStopWords(List<String> tokens) {
        return tokens.stream()
                .filter(t -> !STOP_WORDS.contains(t))
                .collect(Collectors.toList());
    }

    private String buildFtsQuery(List<String> tokens) {
        // токены для to_tsquery: только буквы/цифры (без операторов &|! и кавычек),
        // префикс :* для prefix-matching
        List<String> ftsParts = tokens.stream()
                .map(SearchQueryNormalizer::ftsToken)
                .filter(t -> !t.isEmpty())
                .distinct()
                .map(t -> t + ":*")
                .toList();
        return joinCapped(ftsParts, " & ");
    }

    private static String ftsToken(String token) {
        return token.replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private String buildFuzzyQuery(List<String> tokens) {
        return joinCapped(tokens.stream()
                .filter(t -> t.length() >= MIN_FUZZY_LENGTH)
                .map(t -> t + "%")
                .toList(), " | ");
    }

    /** Склеивает части, не превышая MAX_QUERY_LENGTH; одиночная длинная часть обрезается. */
    private String joinCapped(List<String> parts, String separator) {
        if (parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.isEmpty()) {
                sb.append(part.length() > MAX_QUERY_LENGTH
                        ? part.substring(0, MAX_QUERY_LENGTH)
                        : part);
            } else if (sb.length() + separator.length() + part.length() <= MAX_QUERY_LENGTH) {
                sb.append(separator).append(part);
            } else {
                break;
            }
        }
        return sb.toString();
    }

    public record NormalizedQuery(
            String ftsQuery,
            String fuzzyQuery,
            List<String> expandedTokens,
            boolean shouldUseFuzzy,
            List<String> originalTokens) {

        public static NormalizedQuery empty() {
            return new NormalizedQuery("", "", List.of(), false, List.of());
        }
    }
}
