package com.okututor.backend.search.understanding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.okututor.backend.search.understanding.StructuredQuery.Intent;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Разбор и ЖЁСТКАЯ валидация JSON, пришедшего от LLM (спека #8, #36):
 * неизвестные/невалидные значения отбрасываются в null, диапазонные поля
 * проверяются. LLM-выводу не доверяем: результат — только StructuredQuery.
 */
public final class StructuredQuerySanitizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> SUBJECTS = Set.of(
            "MATHEMATICS", "ENGLISH", "RUSSIAN", "KYRGYZ", "PHYSICS", "CHEMISTRY",
            "BIOLOGY", "HISTORY", "PROGRAMMING", "DESIGN", "MUSIC");
    private static final Set<String> LEVELS = Set.of("BEGINNER", "INTERMEDIATE", "ADVANCED");
    private static final Set<String> FORMATS = Set.of("ONLINE", "OFFLINE");
    private static final Set<String> LANGUAGES = Set.of("RU", "KG", "EN");
    private static final BigDecimal PRICE_CAP = new BigDecimal("100000000");

    private StructuredQuerySanitizer() {
    }

    /** Парсит JSON-строку (допускаются markdown-обёртки ```json). */
    public static Optional<StructuredQuery> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            String cleaned = stripCodeFences(json);
            JsonNode root = MAPPER.readTree(cleaned);
            if (root == null || !root.isObject()) {
                return Optional.empty();
            }
            return Optional.of(sanitize(root));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static StructuredQuery sanitize(JsonNode root) {
        Intent intent = "FIND_TUTOR".equals(text(root, "intent")) ? Intent.FIND_TUTOR : Intent.FIND_COURSE;
        return new StructuredQuery(
                intent,
                enumValue(root, "subject", SUBJECTS),
                boundedText(root, "technology", 30),
                boundedText(root, "goal", 30),
                grade(root),
                enumValue(root, "format", FORMATS),
                price(root, "price_max", "priceMax"),
                price(root, "price_min", "priceMin"),
                enumValue(root, "level", LEVELS),
                enumValue(root, "language", LANGUAGES),
                false);
    }

    private static String stripCodeFences(String json) {
        String trimmed = json.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String enumValue(JsonNode root, String field, Set<String> allowed) {
        String value = text(root, field);
        if (value == null) {
            return null;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(upper) ? upper : null;
    }

    private static String boundedText(JsonNode root, String field, int maxLength) {
        String value = text(root, field);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toUpperCase(Locale.ROOT);
        return (trimmed.isEmpty() || trimmed.length() > maxLength) ? null : trimmed;
    }

    private static Integer grade(JsonNode root) {
        JsonNode node = root.get("grade");
        if (node == null || node.isNull() || !node.canConvertToInt()) {
            return null;
        }
        int grade = node.asInt();
        return (grade >= 1 && grade <= 12) ? grade : null;
    }

    private static BigDecimal price(JsonNode root, String snakeName, String camelName) {
        JsonNode node = root.get(snakeName);
        if (node == null || node.isNull()) {
            node = root.get(camelName);
        }
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        BigDecimal value = node.decimalValue();
        return (value.signum() < 0 || value.compareTo(PRICE_CAP) > 0) ? null : value;
    }
}
