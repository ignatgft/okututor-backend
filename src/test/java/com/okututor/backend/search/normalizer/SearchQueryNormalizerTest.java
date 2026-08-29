package com.okututor.backend.search.normalizer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SearchQueryNormalizerTest {

    private final KeyboardLayoutNormalizer keyboardNormalizer = new KeyboardLayoutNormalizer();
    private final SynonymExpander synonymExpander = new SynonymExpander();
    private final SearchQueryNormalizer normalizer = new SearchQueryNormalizer(keyboardNormalizer, synonymExpander);

    @Test
    void emptyQuery_returnsEmptyNormalizedQuery() {
        SearchQueryNormalizer.NormalizedQuery result = normalizer.normalize("");
        assertTrue(result.originalTokens().isEmpty());
        assertEquals("", result.ftsQuery());
        assertFalse(result.shouldUseFuzzy());
    }

    @Test
    void nullQuery_returnsEmptyNormalizedQuery() {
        SearchQueryNormalizer.NormalizedQuery result = normalizer.normalize(null);
        assertTrue(result.originalTokens().isEmpty());
    }

    @Test
    void trimsAndLowercases() {
        SearchQueryNormalizer.NormalizedQuery result = normalizer.normalize("  JaVa  ");
        assertEquals(1, result.originalTokens().size());
        assertEquals("java", result.originalTokens().get(0));
        assertEquals("java:*", result.ftsQuery());
    }

    @Test
    void multiWordQuery_tokenizes() {
        SearchQueryNormalizer.NormalizedQuery result = normalizer.normalize("java backend");
        assertEquals(2, result.originalTokens().size());
        assertEquals("java:* & backend:*", result.ftsQuery());
    }

    @Test
    void removesStopWords() {
        SearchQueryNormalizer.NormalizedQuery result = normalizer.normalize("java for beginners");
        assertTrue(result.originalTokens().contains("java"));
        assertTrue(result.originalTokens().contains("beginners"));
        assertFalse(result.originalTokens().contains("for"));
    }

    @Test
    void handlesUnicodeNormalization() {
        SearchQueryNormalizer.NormalizedQuery result = normalizer.normalize("café");
        assertEquals("café", result.originalTokens().get(0));
    }

    @Test
    void maxQueryLength_isEnforced() {
        String longQuery = "a".repeat(250);
        SearchQueryNormalizer.NormalizedQuery result = normalizer.normalize(longQuery);
        assertTrue(result.ftsQuery().length() <= 200);
    }

    @Test
    void stopWordsOnly_returnsEmptyTokens() {
        SearchQueryNormalizer.NormalizedQuery result = normalizer.normalize("для и the");
        assertTrue(result.originalTokens().isEmpty());
        assertEquals("", result.ftsQuery());
    }

    @Test
    void cyrillicQuery_notCorruptedByLayoutCorrection() {
        SearchQueryNormalizer.NormalizedQuery result = normalizer.normalize("математика");
        assertEquals(1, result.originalTokens().size());
        assertEquals("математика", result.originalTokens().get(0));
        assertEquals("математика:*", result.ftsQuery());
    }

    @Test
    void synonymExpansion_addsCanonicalAndAliases() {
        SearchQueryNormalizer.NormalizedQuery result = normalizer.normalize("джава");
        assertTrue(result.expandedTokens().contains("java"));
        assertTrue(result.expandedTokens().contains("джава"));
    }

    @Test
    void ftsQuery_isSafeForToTsquery() {
        // операторы/кавычки/скобки не должны попадать в to_tsquery-строку
        SearchQueryNormalizer.NormalizedQuery result = normalizer.normalize("c++ what's (test) & | !");
        assertTrue(result.ftsQuery().matches("[\\p{L}\\p{N}:*& ]+"),
                "ftsQuery содержит небезопасные символы: " + result.ftsQuery());
    }
}