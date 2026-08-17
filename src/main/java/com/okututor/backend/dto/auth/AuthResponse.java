package com.okututor.backend.dto.auth;

import com.okututor.backend.dto.user.UserProfileResponse;

public record AuthResponse(String token, UserProfileResponse user) {
}

