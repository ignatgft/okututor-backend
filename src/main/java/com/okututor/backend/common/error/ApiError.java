package com.okututor.backend.common.error;

import com.okututor.backend.common.web.RequestCorrelationFilter;
import java.util.Map;

/**
 * контракт тела ошибки, который парсит front_okututor errorMapper.js:
 * message <- `message` или `error`; fieldErrors <- `errors`.
 */
public record ApiError(
        int status,
        String message,
        String error,
        Map<String, String> errors,
        String traceId
) {

    public static ApiError of(int status, String code, String message) {
        return new ApiError(status, message, code, null, newTraceId());
    }

    public static ApiError of(int status, String code, String message, Map<String, String> fieldErrors) {
        return new ApiError(status, message, code, fieldErrors, newTraceId());
    }

    private static String newTraceId() {
        // переиспользуем requestId из MDC (RequestCorrelationFilter), чтобы traceId
        // в теле ошибки совпадал с requestId в логах запроса; иначе — новый
        String mdc = org.slf4j.MDC.get(RequestCorrelationFilter.MDC_KEY);
        if (mdc != null && !mdc.isBlank()) {
            return mdc;
        }
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
