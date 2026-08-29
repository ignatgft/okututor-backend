package com.okututor.backend.search.understanding;

import java.util.Optional;

/**
 * AI-разбор запроса (спека #7, #8). Вызывается только для сложных запросов при
 * {@code search.ai.enabled=true}; обязан вернуть только валидный StructuredQuery
 * или empty (fallback на rules). Никогда не работает с БД/SQL/сущностями.
 */
public interface AiQueryParser {

    Optional<StructuredQuery> parse(String rawQuery);
}
