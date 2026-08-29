package com.okututor.backend.search.understanding;

import com.okututor.backend.search.SearchProperties;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Понимание запроса (спека #3, #6, #7): сначала rules+словари (без внешних вызовов),
 * AI — только для сложных запросов и только если включён. Не выбирает курсы и не
 * трогает БД: возвращает только StructuredQuery.
 */
public class QueryUnderstandingService {

    private static final Logger log = LoggerFactory.getLogger(QueryUnderstandingService.class);

    /** Запрос считается сложным при таком числе токенов (порог вызова AI). */
    private static final int COMPLEX_QUERY_TOKENS = 5;

    private final RuleBasedQueryParser ruleParser;
    private final AiQueryParser aiParser;
    private final SearchProperties props;

    public QueryUnderstandingService(RuleBasedQueryParser ruleParser,
                                     AiQueryParser aiParser,
                                     SearchProperties props) {
        this.ruleParser = ruleParser;
        this.aiParser = aiParser;
        this.props = props;
    }

    public StructuredQuery understand(String rawQuery) {
        StructuredQuery rules = ruleParser.parse(rawQuery);

        if (!props.getAi().isEnabled()) {
            return rules;
        }
        if (!isComplex(rawQuery)) {
            // простые запросы («математика», «java») — без AI (спека #4)
            return rules;
        }
        try {
            Optional<StructuredQuery> ai = aiParser.parse(rawQuery);
            if (ai.isPresent()) {
                return merge(rules, ai.get().withAiAssisted(true));
            }
        } catch (RuntimeException e) {
            log.warn("Query understanding: AI fallback to rules: {}", e.toString());
        }
        return rules;
    }

    private boolean isComplex(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return false;
        }
        return rawQuery.trim().split("\\s+").length >= COMPLEX_QUERY_TOKENS;
    }

    /** Детерминированные rules имеют приоритет; AI дополняет неизвлечённое. */
    private StructuredQuery merge(StructuredQuery rules, StructuredQuery ai) {
        return new StructuredQuery(
                rules.intent() != null ? rules.intent() : ai.intent(),
                firstNonNull(rules.subject(), ai.subject()),
                firstNonNull(rules.technology(), ai.technology()),
                firstNonNull(rules.goal(), ai.goal()),
                firstNonNull(rules.grade(), ai.grade()),
                firstNonNull(rules.format(), ai.format()),
                firstNonNull(rules.priceMax(), ai.priceMax()),
                firstNonNull(rules.priceMin(), ai.priceMin()),
                firstNonNull(rules.level(), ai.level()),
                firstNonNull(rules.language(), ai.language()),
                true);
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }
}
