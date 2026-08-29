package com.okututor.backend.auth.dto;

/** ответ регистрации: { status: EMAIL_VERIFICATION_REQUIRED, email }. */
public record StatusResponse(String status, String email) {

    public static StatusResponse emailVerificationRequired(String email) {
        return new StatusResponse("EMAIL_VERIFICATION_REQUIRED", email);
    }

    public static StatusResponse verificationCodeSent(String email) {
        return new StatusResponse("VERIFICATION_CODE_SENT", email);
    }
}
