package com.okututor.backend.auth.dto;

import com.okututor.backend.user.dto.UserResponse;

/** успешная верификация email: { status: EMAIL_VERIFIED, access_token, refresh_token, user }. */
public record VerifiedEmailResponse(
        String status,
        String access_token,
        String refresh_token,
        UserResponse user
) {
}
