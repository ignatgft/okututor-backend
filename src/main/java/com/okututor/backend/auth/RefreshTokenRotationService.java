package com.okututor.backend.auth;

import com.okututor.backend.auth.dto.AuthTokensResponse;
import com.okututor.backend.security.JwtService;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * выпуск и ротация токенов: обмен refresh на новую пару, logout,
 * revoke всех сессий. Выделен из AuthService, чтобы другие сервисы
 * (email-верификация, OAuth) зависели от узкого контракта, а не от God-сервиса.
 */
@Service
public class RefreshTokenRotationService {

    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final UserMapper userMapper;

    public RefreshTokenRotationService(JwtService jwtService,
                                       RefreshTokenService refreshTokenService,
                                       UserMapper userMapper) {
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.userMapper = userMapper;
    }

    @Transactional
    public AuthTokensResponse refresh(String refreshToken, SessionInfo session) {
        RefreshTokenService.RotationResult rotation = refreshTokenService.rotate(refreshToken, session);
        return buildTokenPair(rotation.user(), session);
    }

    @Transactional
    public void logout(String refreshToken) {
        refreshTokenService.logout(refreshToken);
    }

    /** Выход со всех устройств: ревок всех активных сессий пользователя. */
    @Transactional
    public void logoutAll(java.util.UUID userId) {
        refreshTokenService.revokeAllForUser(userId);
    }

    /** revoke всех refresh-токенов пользователя (смена email/пароля). */
    @Transactional
    public void revokeAllForUser(java.util.UUID userId) {
        refreshTokenService.revokeAllForUser(userId);
    }

    public AuthTokensResponse buildTokenPair(User user) {
        return buildTokenPair(user, SessionInfo.empty());
    }

    public AuthTokensResponse buildTokenPair(User user, SessionInfo session) {
        String access = jwtService.generateAccessToken(user.getId(), user.getEmail(), user.getRole());
        String refresh = refreshTokenService.issueFor(user, session);
        return new AuthTokensResponse(access, refresh, userMapper.toResponse(user));
    }
}
