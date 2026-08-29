package com.okututor.backend.course;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/** хранит LocationType в нижнем регистре ('online'/'offline') — как в CHECK-констрейнте и контракте API. */
@Converter
public class LocationTypeConverter implements AttributeConverter<Course.LocationType, String> {

    @Override
    public String convertToDatabaseColumn(Course.LocationType attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public Course.LocationType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : Course.LocationType.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
