package com.okututor.backend.search.understanding;

import static org.assertj.core.api.Assertions.assertThat;

import com.okututor.backend.search.understanding.StructuredQuery.Intent;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RuleBasedQueryParserTest {

    private final RuleBasedQueryParser parser = new RuleBasedQueryParser();

    @Test
    void simpleSubjectQuery_extractsSubjectOnly() {
        StructuredQuery sq = parser.parse("математика");
        assertThat(sq.subject()).isEqualTo("MATHEMATICS");
        assertThat(sq.intent()).isEqualTo(Intent.FIND_COURSE);
        assertThat(sq.priceMax()).isNull();
        assertThat(sq.grade()).isNull();
    }

    @Test
    void simpleEnglishQueries_extractEnglishSubject() {
        assertThat(parser.parse("английский").subject()).isEqualTo("ENGLISH");
        assertThat(parser.parse("англис тили").subject()).isEqualTo("ENGLISH");
        assertThat(parser.parse("English tutor").subject()).isEqualTo("ENGLISH");
        assertThat(parser.parse("репетитор английского").subject()).isEqualTo("ENGLISH");
        assertThat(parser.parse("англис тили мугалими").subject()).isEqualTo("ENGLISH");
    }

    @Test
    void tutorIntent_detected() {
        assertThat(parser.parse("репетитор английского").intent()).isEqualTo(Intent.FIND_TUTOR);
        assertThat(parser.parse("англис тили мугалими").intent()).isEqualTo(Intent.FIND_TUTOR);
        assertThat(parser.parse("English tutor").intent()).isEqualTo(Intent.FIND_TUTOR);
        assertThat(parser.parse("курс java").intent()).isEqualTo(Intent.FIND_COURSE);
    }

    @Test
    void complexQuery_extractsAllFilters() {
        StructuredQuery sq = parser.parse(
                "репетитор по математике для подготовки к ОРТ 10 класс онлайн до 1000 сом");
        assertThat(sq.intent()).isEqualTo(Intent.FIND_TUTOR);
        assertThat(sq.subject()).isEqualTo("MATHEMATICS");
        assertThat(sq.goal()).isEqualTo("ORT");
        assertThat(sq.grade()).isEqualTo(10);
        assertThat(sq.format()).isEqualTo("ONLINE");
        assertThat(sq.priceMax()).isEqualByComparingTo(new BigDecimal("1000"));
    }

    @Test
    void javaCourse_extractsTechnologyAndLevel() {
        StructuredQuery sq = parser.parse("курс Java для начинающих");
        assertThat(sq.subject()).isEqualTo("PROGRAMMING");
        assertThat(sq.technology()).isEqualTo("JAVA");
        assertThat(sq.level()).isEqualTo("BEGINNER");
    }

    @Test
    void gradeVariants_parsed() {
        assertThat(parser.parse("математика 5 класс").grade()).isEqualTo(5);
        assertThat(parser.parse("класс 11 математика").grade()).isEqualTo(11);
        assertThat(parser.parse("математика 13 класс").grade()).isNull();
        assertThat(parser.parse("математика").grade()).isNull();
    }

    @Test
    void priceVariants_parsed() {
        assertThat(parser.parse("английский до 500").priceMax()).isEqualByComparingTo("500");
        assertThat(parser.parse("английский не дороже 700").priceMax()).isEqualByComparingTo("700");
        assertThat(parser.parse("английский от 300").priceMin()).isEqualByComparingTo("300");
        assertThat(parser.parse("english under 1200").priceMax()).isEqualByComparingTo("1200");
    }

    @Test
    void formatVariants_parsed() {
        assertThat(parser.parse("математика онлайн").format()).isEqualTo("ONLINE");
        assertThat(parser.parse("математика offline").format()).isEqualTo("OFFLINE");
        assertThat(parser.parse("математика офлайн").format()).isEqualTo("OFFLINE");
    }

    @Test
    void goals_detected() {
        assertThat(parser.parse("подготовка к ОРТ").goal()).isEqualTo("ORT");
        assertThat(parser.parse("IELTS preparation").goal()).isEqualTo("IELTS");
        assertThat(parser.parse("подготовка к экзамену").goal()).isEqualTo("EXAM");
    }

    @Test
    void blankQuery_returnsEmpty() {
        assertThat(parser.parse(null)).isEqualTo(StructuredQuery.empty());
        assertThat(parser.parse("   ").hasExtractedFilters()).isFalse();
    }

    @Test
    void wordBoundaries_preventFalseMatches() {
        // "мат" не должен матчить внутри слова; "до" — внутри слова
        assertThat(parser.parse("формат").subject()).isNull();
        assertThat(parser.parse("доска").priceMax()).isNull();
    }

    @Test
    void subjectAliases_returnsKeywordsForHardFilter() {
        assertThat(parser.subjectAliases("MATHEMATICS")).contains("математика", "math");
        assertThat(parser.subjectAliases("UNKNOWN")).isEmpty();
        assertThat(parser.subjectAliases(null)).isEmpty();
    }

    @Test
    void technologyExtraction_singleTechToken() {
        StructuredQuery sq = parser.parse("пайтон");
        assertThat(sq.technology()).isEqualTo("PYTHON");
        assertThat(sq.subject()).isEqualTo("PROGRAMMING");

        assertThat(parser.parse("питон").technology()).isEqualTo("PYTHON");
        assertThat(parser.parse("python").technology()).isEqualTo("PYTHON");
        assertThat(parser.parse("джава").technology()).isEqualTo("JAVA");
        assertThat(parser.parse("javascript").technology()).isEqualTo("JAVASCRIPT");
        assertThat(parser.parse("kotlin").technology()).isEqualTo("KOTLIN");
        assertThat(parser.parse("свифт").technology()).isEqualTo("SWIFT");
    }

    @Test
    void technologyAndSubject_coexist() {
        StructuredQuery sq = parser.parse("python programming");
        assertThat(sq.technology()).isEqualTo("PYTHON");
        assertThat(sq.subject()).isEqualTo("PROGRAMMING");

        StructuredQuery ru = parser.parse("курс программирования на пайтон");
        assertThat(ru.technology()).isEqualTo("PYTHON");
        assertThat(ru.subject()).isEqualTo("PROGRAMMING");
    }

    @Test
    void technologyAliases_returnsAllForms() {
        assertThat(parser.technologyAliases("PYTHON"))
                .contains("python", "пайтон", "питон", "питона");
        assertThat(parser.technologyAliases("JAVASCRIPT")).contains("javascript", "js");
        assertThat(parser.technologyAliases(null)).isEmpty();
        assertThat(parser.technologyAliases("UNKNOWN")).isEmpty();
    }

    @Test
    void geographySubject_detected() {
        assertThat(parser.parse("география").subject()).isEqualTo("GEOGRAPHY");
        assertThat(parser.parse("географии").subject()).isEqualTo("GEOGRAPHY");
    }
}
