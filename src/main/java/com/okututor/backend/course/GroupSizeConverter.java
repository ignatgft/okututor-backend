package com.okututor.backend.course;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/** хранит GroupSize в нижнем регистре ('individual'/'group') — как в CHECK-констрейнте и контракте API. */
@Converter
public class GroupSizeConverter implements AttributeConverter<Course.GroupSize, String> {

    @Override
    public String convertToDatabaseColumn(Course.GroupSize attribute) {
        return attribute == null ? null : attribute.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public Course.GroupSize convertToEntityAttribute(String dbData) {
        return dbData == null ? null : Course.GroupSize.valueOf(dbData.toUpperCase(Locale.ROOT));
    }
}
