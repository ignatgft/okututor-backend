package com.okututor.backend.common.config;

import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;
import org.springframework.context.annotation.Configuration;

/**
 * Убирает warning "Serializing PageImpl instances as-is is not supported".
 * Формат тела ответов остаётся прежним плоским конвертом
 * {content, page, size, total_elements, total_pages, first, last} —
 * его обеспечивает PageJacksonSerializer; VIA_DTO здесь страховка,
 * если сериализатор когда-нибудь уберут.
 */
@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
public class PagingConfig {
}
