package com.okututor.backend.common.error;

import org.springframework.http.HttpStatus;

/**
 * базовый runtime-эксепшен с HTTP-статусом и кодом ошибки для фронта.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, message);
    }

    /** 403 с произвольным кодом (напр. MEETING_NOT_AVAILABLE, REVIEW_NOT_ALLOWED). */
    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFLICT, message);
    }

    public static ApiException validation(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCodes.VALIDATION_ERROR, message);
    }

    public static ApiException rateLimited(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RATE_LIMITED, message);
    }

    public static ApiException invalidCode(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_CODE, message);
    }

    public static ApiException codeExpired(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VERIFICATION_CODE_EXPIRED, message);
    }

    public static ApiException tooManyAttempts(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.TOO_MANY_ATTEMPTS, message);
    }
}
