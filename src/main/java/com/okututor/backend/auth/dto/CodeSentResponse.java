package com.okututor.backend.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** ответ resend-verification; каноничный контракт — только snake_case. */
public record CodeSentResponse(
        String status,
        @JsonProperty("expires_in") long expires_in,
        @JsonProperty("resend_available_in") long resend_available_in
) {

    public static CodeSentResponse resendVerification() {
        return new CodeSentResponse("VERIFICATION_CODE_SENT", 600, 60);
    }
}
