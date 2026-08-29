package com.okututor.backend.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Map;

/**
 * Конвертер Map<Long,Object> <-> JSONB-колонки notifications.payload.
 * Храним и читаем через JSON-строку; Jackson уже настроен на snake_case
 * (ключи payload остаются такими, как положили — enrollment_id и т.п.).
 */
@Converter
public class JsonMapConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<java.util.LinkedHashMap<String, Object>> TYPE =
            new TypeReference<java.util.LinkedHashMap<String, Object>>() {
            };

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        try {
            return attribute == null ? null : MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize notification payload", e);
        }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        try {
            return dbData == null ? null : MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            return null;
        }
    }
}
