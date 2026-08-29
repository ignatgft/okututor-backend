package com.okututor.backend.common.error;

import java.util.Map;

/** 422 VALIDATION_ERROR с ошибками по полям (карта `errors` для фронта). */
public class FieldValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public FieldValidationException(Map<String, String> fieldErrors) {
        super("Validation failed");
        this.fieldErrors = fieldErrors;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
