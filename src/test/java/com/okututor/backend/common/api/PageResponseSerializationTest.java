package com.okututor.backend.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * фронт читает ключи пагинации как total_elements/total_pages (mockData.js).
 * зеркалит глобальную стратегию SNAKE_CASE из JacksonConfig.
 */
class PageResponseSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    record Item(long id, String fullName) {}

    @Test
    void pageEnvelopeUsesSnakeCaseKeys() throws Exception {
        PageResponse<Item> page = new PageResponse<>(List.of(new Item(1, "Test User")), 0, 20, 21, 2, true, false);
        String json = mapper.writeValueAsString(page);

        assertThat(json)
                .contains("\"total_elements\":21")
                .contains("\"total_pages\":2")
                .contains("\"full_name\":\"Test User\"")
                .doesNotContain("totalElements");
    }

    @Test
    void instantIsSerializedAsIso8601() throws Exception {
        record Dated(Instant createdAt) {}
        String json = mapper.writeValueAsString(new Dated(Instant.parse("2026-08-20T10:00:00Z")));
        assertThat(json).contains("2026-08-20T10:00:00Z");
    }
}
