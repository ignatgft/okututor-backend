package com.okututor.backend.search.understanding;

import static org.assertj.core.api.Assertions.assertThat;

import com.okututor.backend.search.SearchProperties;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StructuredQuerySanitizerTest {

    @Test
    void validJson_parsed() {
        String json = """
                {"intent":"FIND_COURSE","subject":"MATHEMATICS","goal":"ORT","grade":10,
                 "format":"ONLINE","price_max":1000,"level":"BEGINNER","language":"RU"}
                """;
        StructuredQuery sq = StructuredQuerySanitizer.fromJson(json).orElseThrow();
        assertThat(sq.intent()).isEqualTo(StructuredQuery.Intent.FIND_COURSE);
        assertThat(sq.subject()).isEqualTo("MATHEMATICS");
        assertThat(sq.goal()).isEqualTo("ORT");
        assertThat(sq.grade()).isEqualTo(10);
        assertThat(sq.format()).isEqualTo("ONLINE");
        assertThat(sq.priceMax()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(sq.level()).isEqualTo("BEGINNER");
        assertThat(sq.language()).isEqualTo("RU");
    }

    @Test
    void markdownWrappedJson_parsed() {
        String json = "```json\n{\"subject\":\"ENGLISH\"}\n```";
        assertThat(StructuredQuerySanitizer.fromJson(json).orElseThrow().subject()).isEqualTo("ENGLISH");
    }

    @Test
    void unknownEnumValues_droppedToNull() {
        String json = """
                {"subject":"QUANTUM_PHYSICS_XYZ","format":"HYBRID","level":"GURU","language":"XX"}
                """;
        StructuredQuery sq = StructuredQuerySanitizer.fromJson(json).orElseThrow();
        assertThat(sq.subject()).isNull();
        assertThat(sq.format()).isNull();
        assertThat(sq.level()).isNull();
        assertThat(sq.language()).isNull();
    }

    @Test
    void outOfRangeValues_dropped() {
        String json = """
                {"grade":99,"price_max":-5,"price_min":999999999999}
                """;
        StructuredQuery sq = StructuredQuerySanitizer.fromJson(json).orElseThrow();
        assertThat(sq.grade()).isNull();
        assertThat(sq.priceMax()).isNull();
        assertThat(sq.priceMin()).isNull();
    }

    @Test
    void malformedJson_returnsEmpty() {
        assertThat(StructuredQuerySanitizer.fromJson("not a json")).isEmpty();
        assertThat(StructuredQuerySanitizer.fromJson("{\"subject\":")).isEmpty();
        assertThat(StructuredQuerySanitizer.fromJson(null)).isEmpty();
        assertThat(StructuredQuerySanitizer.fromJson("")).isEmpty();
        assertThat(StructuredQuerySanitizer.fromJson("[1,2,3]")).isEmpty();
    }

    @Test
    void camelCasePriceKeys_alsoSupported() {
        StructuredQuery sq = StructuredQuerySanitizer.fromJson("{\"priceMax\":42}").orElseThrow();
        assertThat(sq.priceMax()).isEqualByComparingTo("42");
    }
}

class QueryUnderstandingServiceTest {

    private final RuleBasedQueryParser ruleParser = new RuleBasedQueryParser();

    private QueryUnderstandingService service(boolean aiEnabled, AiQueryParser aiParser) {
        SearchProperties props = new SearchProperties();
        props.getAi().setEnabled(aiEnabled);
        return new QueryUnderstandingService(ruleParser, aiParser, props);
    }

    @Test
    void aiDisabled_usesRulesOnly() {
        var sq = service(false, raw -> Optional.of(
                new StructuredQuery(StructuredQuery.Intent.FIND_TUTOR, "ENGLISH", null, null,
                        null, null, null, null, null, null, true)))
                .understand("репетитор по математике для подготовки к ОРТ 10 класс онлайн до 1000 сом");
        assertThat(sq.aiAssisted()).isFalse();
        assertThat(sq.subject()).isEqualTo("MATHEMATICS");
        assertThat(sq.priceMax()).isEqualByComparingTo("1000");
    }

    @Test
    void simpleQuery_neverCallsAi() {
        AiQueryParser exploding = raw -> {
            throw new AssertionError("AI must not be called for simple queries");
        };
        var sq = service(true, exploding).understand("математика");
        assertThat(sq.subject()).isEqualTo("MATHEMATICS");
        assertThat(sq.aiAssisted()).isFalse();
    }

    @Test
    void complexQuery_aiFillsGaps_rulesTakePriority() {
        AiQueryParser ai = raw -> Optional.of(new StructuredQuery(
                StructuredQuery.Intent.FIND_COURSE, "PHYSICS", "JAVA", null, 9,
                "OFFLINE", new BigDecimal("1"), null, null, "KG", true));
        var sq = service(true, ai).understand(
                "курс Java для подготовки к ОРТ 10 класс онлайн до 1000 сом");
        assertThat(sq.aiAssisted()).isTrue();
        assertThat(sq.subject()).isEqualTo("PROGRAMMING");      // rules win
        assertThat(sq.technology()).isEqualTo("JAVA");           // rules win
        assertThat(sq.grade()).isEqualTo(10);                    // rules win
        assertThat(sq.format()).isEqualTo("ONLINE");             // rules win
        assertThat(sq.priceMax()).isEqualByComparingTo("1000");  // rules win
        assertThat(sq.language()).isEqualTo("KG");               // AI fills the gap
    }

    @Test
    void aiFailure_fallsBackToRules() {
        AiQueryParser failing = raw -> {
            throw new RuntimeException("provider timeout");
        };
        var sq = service(true, failing).understand("репетитор английского онлайн до 500 сом недорого");
        assertThat(sq.subject()).isEqualTo("ENGLISH");
        assertThat(sq.aiAssisted()).isFalse();
    }

    @Test
    void aiEmptyResult_fallsBackToRules() {
        var sq = service(true, raw -> Optional.empty())
                .understand("подготовка к ОРТ по математике 11 класс онлайн");
        assertThat(sq.subject()).isEqualTo("MATHEMATICS");
        assertThat(sq.goal()).isEqualTo("ORT");
        assertThat(sq.aiAssisted()).isFalse();
    }
}
