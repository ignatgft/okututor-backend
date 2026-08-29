package com.okututor.backend.search.understanding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.okututor.backend.search.SearchProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Опциональный AI-парсер (OpenAI-совместимый chat-completions), спека #7.
 * Активен только при {@code search.ai.enabled=true} и заданном endpoint.
 * Любой сбой (таймаут, сеть, кривой JSON) → empty → fallback на rule-based parser.
 * Возвращает ТОЛЬКО провалидированный StructuredQuery (спека #8).
 */
public class HttpAiQueryParser implements AiQueryParser {

    private static final Logger log = LoggerFactory.getLogger(HttpAiQueryParser.class);

    private static final String SYSTEM_PROMPT = """
            You parse tutoring marketplace search queries into strict JSON.
            Return ONLY a JSON object, no explanations, no markdown, with fields:
            intent ("FIND_COURSE" or "FIND_TUTOR"), subject (e.g. MATHEMATICS, ENGLISH, \
            PROGRAMMING, PHYSICS or null), technology (e.g. JAVA, PYTHON or null), \
            goal (e.g. ORT, IELTS, EXAM or null), grade (integer 1-12 or null), \
            format ("ONLINE" or "OFFLINE" or null), price_max (number or null), \
            price_min (number or null), level ("BEGINNER", "INTERMEDIATE", "ADVANCED" or null), \
            language ("RU", "KG", "EN" or null).""";

    private final SearchProperties.Ai config;
    private final String endpoint;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpAiQueryParser(SearchProperties.Ai config, String endpoint, String apiKey) {
        this.config = config;
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(250, config.getTimeoutMs())))
                .build();
    }

    @Override
    public Optional<StructuredQuery> parse(String rawQuery) {
        if (endpoint.isEmpty()) {
            log.debug("AI parser enabled but endpoint is not configured; skipping");
            return Optional.empty();
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            if (config.getModel() != null && !config.getModel().isBlank()) {
                body.put("model", config.getModel());
            }
            body.put("max_tokens", config.getMaxTokens());
            com.fasterxml.jackson.databind.node.ArrayNode messages = body.putArray("messages");
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", SYSTEM_PROMPT);
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", rawQuery);

            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofMillis(Math.max(250, config.getTimeoutMs())))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (!apiKey.isEmpty()) {
                request.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = httpClient.send(request.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("AI parser returned HTTP {}", response.statusCode());
                return Optional.empty();
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) {
                return Optional.empty();
            }
            return StructuredQuerySanitizer.fromJson(content.asText());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("AI parser failed, falling back to rules: {}", e.toString());
            return Optional.empty();
        }
    }
}
