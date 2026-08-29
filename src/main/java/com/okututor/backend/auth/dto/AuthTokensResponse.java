package com.okututor.backend.auth.dto;

import com.okututor.backend.user.dto.UserResponse;

/** ответ успешного логина/верификации email: { access_token, refresh_token, user }. */
public record AuthTokensResponse(
        String access_token,
        String refresh_token,
        UserResponse user
) {
}
