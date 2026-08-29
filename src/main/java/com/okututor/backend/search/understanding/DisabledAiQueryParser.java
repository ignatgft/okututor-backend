package com.okututor.backend.search.understanding;

import java.util.Optional;

/** AI-парсер выключен (по умолчанию): поиск полностью работает на rules. */
public class DisabledAiQueryParser implements AiQueryParser {

    @Override
    public Optional<StructuredQuery> parse(String rawQuery) {
        return Optional.empty();
    }
}
