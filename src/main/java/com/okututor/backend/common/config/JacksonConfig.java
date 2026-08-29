package com.okututor.backend.common.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.okututor.backend.common.api.PageJacksonSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;

/**
 * глобальные JSON-конвенции, которых требует front_okututor:
 * все ключи payload — snake_case (access_token, full_name, total_elements, ...),
 * неизвестные свойства игнорируются ради совместимости вперёд.
 */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer okututorJacksonCustomizer() {
        return builder -> builder
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .failOnUnknownProperties(false)
                // Page сериализуем плоским конвертом: исчезает warning
                // "Serializing PageImpl instances as-is is not supported",
                // а форма ответа не меняется для фронта
                .serializerByType(Page.class, new PageJacksonSerializer());
    }
}
