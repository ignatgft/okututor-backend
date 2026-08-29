package com.okututor.backend.common.api;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import org.springframework.data.domain.Page;

/**
 * Плоский конверт пагинации, который ждёт фронт (mockData.js):
 * {content, page, size, total_elements, total_pages, first, last}.
 * Ключи snake_case зашиты явно — глобальная стратегия именования на них не влияет.
 */
public class PageJacksonSerializer extends JsonSerializer<Page<?>> {

    @Override
    public void serialize(Page<?> page, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeStartObject();
        gen.writeObjectField("content", page.getContent());
        gen.writeNumberField("page", page.getNumber());
        gen.writeNumberField("size", page.getSize());
        gen.writeNumberField("total_elements", page.getTotalElements());
        gen.writeNumberField("total_pages", page.getTotalPages());
        gen.writeBooleanField("first", page.isFirst());
        gen.writeBooleanField("last", page.isLast());
        gen.writeEndObject();
    }
}
