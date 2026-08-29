package com.okututor.backend.search.normalizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SynonymExpanderTest {

    private final SynonymExpander expander = new SynonymExpander();

    @Test
    void defaultDictionary_groupsContainCanonicalAndAreDisjoint() {
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : expander.getSynonyms().entrySet()) {
            Set<String> terms = entry.getValue();
            assertThat(terms).contains(entry.getKey());
            // обратный индекс непротиворечив: термин принадлежит максимум одной группе
            for (String term : terms) {
                assertThat(seen.add(term))
                        .as("термин '%s' не должен принадлежать нескольким группам", term)
                        .isTrue();
            }
        }
    }

    @Test
    void duplicateEntriesInSourceData_doNotThrowAndAreDeduplicated() {
        String[][] groups = {
                {"python", "пайтон", "питон", "пайтон", "PYTHON", " python "},
                {"java", "джава", "джава", "ява"},
        };
        SynonymExpander custom = new SynonymExpander(groups);

        assertThat(custom.getSynonyms().get("python")).containsExactly("python", "пайтон", "питон");
        assertThat(custom.getSynonyms().get("java")).containsExactly("java", "джава", "ява");
    }

    @Test
    void blankAndNullEntries_areSkipped() {
        String[][] groups = {
                {"math", "математика", null, "  ", "мат"},
                null,
                {},
        };
        assertThatCode(() -> new SynonymExpander(groups)).doesNotThrowAnyException();
        SynonymExpander custom = new SynonymExpander(groups);
        assertThat(custom.getSynonyms().get("math")).containsExactly("math", "математика", "мат");
    }

    @Test
    void aliasCollision_firstGroupWins() {
        String[][] groups = {
                {"git", "гит", "github"},
                {"vcs", "github", "система контроля версий"},
        };
        SynonymExpander custom = new SynonymExpander(groups);
        // «github» остался в первой группе; вторая не перезаписала обратный индекс
        assertThat(custom.expand(List.of("github"))).contains("git", "гит");
    }

    @Test
    void expandsAliasToCanonicalAndSiblings() {
        List<String> expanded = expander.expand(List.of("джава"));
        assertThat(expanded).contains("джава", "java", "ява");
    }

    @Test
    void expandsCanonicalToAliases() {
        List<String> expanded = expander.expand(List.of("java"));
        assertThat(expanded).contains("java", "джава", "ява");
    }

    @Test
    void expansionIsCaseInsensitive() {
        List<String> expanded = expander.expand(List.of("JAVA"));
        assertThat(expanded).contains("java", "джава");
    }

    @Test
    void unknownToken_returnsUnchanged() {
        List<String> expanded = expander.expand(List.of("квантовая"));
        assertThat(expanded).containsExactly("квантовая");
    }

    @Test
    void multiWordSynonym_matches() {
        List<String> expanded = expander.expand(List.of("англис тили"));
        assertThat(expanded).contains("english", "английский");
    }

    @Test
    void techGroups_coverRequiredDomains() {
        assertThat(expander.expand(List.of("питон"))).contains("python", "пайтон");
        assertThat(expander.expand(List.of("котлин"))).contains("kotlin");
        assertThat(expander.expand(List.of("свифт"))).contains("swift");
        assertThat(expander.expand(List.of("джс"))).contains("javascript", "js");
        assertThat(expander.expand(List.of("докер"))).contains("docker");
        assertThat(expander.expand(List.of("nodejs"))).contains("javascript");
        assertThat(expander.expand(List.of("postgresql"))).contains("sql");
    }

    @Test
    void subjectGroups_coverRequiredDomains() {
        assertThat(expander.expand(List.of("мат"))).contains("math", "математика", "mathematics");
        assertThat(expander.expand(List.of("физика"))).contains("physics");
        assertThat(expander.expand(List.of("химия"))).contains("chemistry");
        assertThat(expander.expand(List.of("биология"))).contains("biology");
        assertThat(expander.expand(List.of("история"))).contains("history");
        assertThat(expander.expand(List.of("география"))).contains("geography");
        assertThat(expander.expand(List.of("дизайн"))).contains("design");
        assertThat(expander.expand(List.of("ios"))).contains("mobile", "android");
    }

    @Test
    void kgForms_expand() {
        assertThat(expander.expand(List.of("мугалим"))).contains("tutor", "репетитор");
        assertThat(expander.expand(List.of("оффлайн"))).contains("offline");
        assertThat(expander.expand(List.of("онлайн"))).contains("online");
        assertThat(expander.expand(List.of("агылча"))).contains("english");
        assertThat(expander.expand(List.of("орусча"))).contains("russian");
        assertThat(expander.expand(List.of("программалоо"))).contains("programming");
        assertThat(expander.expand(List.of("окутуучу"))).contains("tutor");
    }

    @Test
    void phraseExpansion_multiWordAliasInRawQuery() {
        // токены по отдельности не матчат группу, но фраза в запросе — матчит
        List<String> expanded = expander.expand(List.of("англис", "тили"), "англис тили");
        assertThat(expanded).contains("english", "английский");

        List<String> ml = expander.expand(List.of("машинное", "обучение"), "курс машинное обучение");
        assertThat(ml).contains("machine learning", "ml");
    }

    @Test
    void phraseExpansion_worksWithoutRawQuery_viaJoinedTokens() {
        // rawQuery не передан — фраза ищется в склейке токенов
        List<String> expanded = expander.expand(List.of("машинное", "обучение"));
        assertThat(expanded).contains("machine learning", "ml");
    }

    @Test
    void phraseExpansion_requiresWordBoundaries() {
        // «англис тили» внутри другого слова не матчит
        List<String> expanded = expander.expand(List.of("x"), "англис тилиx");
        assertThat(expanded).doesNotContain("english");
    }

    @Test
    void ruInflections_expand() {
        assertThat(expander.expand(List.of("математике"))).contains("math", "математика");
        assertThat(expander.expand(List.of("программированию"))).contains("programming");
        assertThat(expander.expand(List.of("питона"))).contains("python");
        assertThat(expander.expand(List.of("кыргыз тилин"))).contains("kyrgyz");
    }

    @Test
    void pit_prefixAlias_expandsToPython() {
        assertThat(expander.expand(List.of("пит"))).contains("python", "пайтон", "питон");
    }

    @Test
    void commonMisspellings_expand() {
        assertThat(expander.expand(List.of("pyton"))).contains("python");
        assertThat(expander.expand(List.of("pythn"))).contains("python");
    }

    @Test
    void edgePunctuation_doesNotBlockExpansion() {
        assertThat(expander.expand(List.of("python!!!"))).contains("python", "пайтон");
        assertThat(expander.expand(List.of("...java"))).contains("джава");
    }

    @Test
    void withDefaultsPlus_mergesConfigGroups() {
        SynonymExpander custom = SynonymExpander.withDefaultsPlus(List.of(
                List.of("flutter", "флатер", "флюттер"),
                List.of("php", "пхп")));
        assertThat(custom.expand(List.of("флатер"))).contains("flutter");
        assertThat(custom.expand(List.of("пхп"))).contains("php");
        // дефолтные группы сохранены
        assertThat(custom.expand(List.of("пайтон"))).contains("python");
    }

    @Test
    void withDefaultsPlus_configAliasDoesNotOverrideDefault() {
        SynonymExpander custom = SynonymExpander.withDefaultsPlus(List.of(
                List.of("snake", "питон")));
        // «питон» остался в python-группе, не перезаписан конфиг-группой
        assertThat(custom.expand(List.of("питон"))).contains("python");
    }

    @Test
    void withDefaultsPlus_nullAndEmptySafe() {
        assertThatCode(() -> SynonymExpander.withDefaultsPlus(null)).doesNotThrowAnyException();
        assertThatCode(() -> SynonymExpander.withDefaultsPlus(List.of()))
                .doesNotThrowAnyException();
    }
}
