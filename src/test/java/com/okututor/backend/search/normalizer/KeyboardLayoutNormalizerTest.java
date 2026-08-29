package com.okututor.backend.search.normalizer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KeyboardLayoutNormalizerTest {

    private final KeyboardLayoutNormalizer normalizer = new KeyboardLayoutNormalizer();

    @Test
    void cyrillicQuery_isNeverCorrupted() {
        assertThat(normalizer.correctLayout("математика")).isEqualTo("математика");
        assertThat(normalizer.correctLayout("английский для начинающих")).isEqualTo("английский для начинающих");
        assertThat(normalizer.correctLayout("англис тили мугалими")).isEqualTo("англис тили мугалими");
    }

    @Test
    void latinTypedInWrongLayout_correctedToCyrillic() {
        // "привет", набранный в английской раскладке
        assertThat(normalizer.correctLayout("ghbdtn")).isEqualTo("привет");
    }

    @Test
    void plainEnglish_isUntouched() {
        assertThat(normalizer.correctLayout("java")).isEqualTo("java");
        assertThat(normalizer.correctLayout("english tutor")).isEqualTo("english tutor");
    }

    @Test
    void mixedLayout_isUntouchedByCorrectLayout() {
        assertThat(normalizer.correctLayout("java курс")).isEqualTo("java курс");
    }

    @Test
    void blankAndNull_returnAsIs() {
        assertThat(normalizer.correctLayout(null)).isNull();
        assertThat(normalizer.correctLayout("  ")).isEqualTo("  ");
    }

    @Test
    void generateAlternatives_providesOriginalAndCorrected() {
        var alts = normalizer.generateAlternatives("ghbdtn");
        assertThat(alts.get("original")).isEqualTo("ghbdtn");
        assertThat(alts.get("corrected")).isEqualTo("привет");
    }
}
