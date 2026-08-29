package com.okututor.backend.auth;

import com.okututor.backend.common.config.AppProperties;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.user.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * непрозрачные refresh-токены: ротация при использовании + детект переиспользования family.
 * короткое grace-окно позволяет конкурентным refresh-запросам фронта (single-flight
 * гонки, восстановление вкладки) проходили без инвалидации всей сессии.
 */
@Service
public class RefreshTokenService {

    private final RefreshTokenRepository repository;
    private final AppProperties properties;
    private final SecureRandom random = new SecureRandom();

    public record RotationResult(String refreshToken, User user) {}

    public RefreshTokenService(RefreshTokenRepository repository, AppProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public String issueFor(User user) {
        return issueFor(user, SessionInfo.empty());
    }

    /** Выдаёт новую сессию с метаданными устройства (device/UA/IP). */
    public String issueFor(User user, SessionInfo session) {
        String raw = randomToken();
        RefreshToken entity = new RefreshToken();
        entity.setUser(user);
        entity.setTokenHash(hash(raw));
        entity.setFamilyId(UUID.randomUUID());
        entity.setExpiresAt(Instant.now().plus(Duration.ofDays(properties.getJwt().getRefreshTtlDays())));
        applySession(entity, session);
        repository.save(entity);
        return raw;
    }

    @Transactional
    public RotationResult rotate(String rawToken) {
        return rotate(rawToken, SessionInfo.empty());
    }

    @Transactional
    public RotationResult rotate(String rawToken, SessionInfo session) {
        if (rawToken == null || rawToken.isBlank()) {
            throw ApiException.unauthorized("Refresh token is required");
        }
        RefreshToken stored = repository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));

        if (stored.isRevoked()) {
            revokeFamily(stored.getFamilyId());
            throw ApiException.unauthorized("Refresh token has been revoked");
        }

        Instant now = Instant.now();
        Duration grace = Duration.ofSeconds(properties.getJwt().getRefreshGraceSeconds());

        if (stored.isExpired()) {
            revokeFamily(stored.getFamilyId());
            throw ApiException.unauthorized("Refresh token expired");
        }

        boolean withinGrace = stored.getRotatedAt() != null
                && stored.getRotatedAt().isAfter(now.minus(grace));
        if (stored.getRotatedAt() != null && !withinGrace) {
            // повторное использование уже ротированного токена вне grace-окна: считаем это кражей токена
            revokeFamily(stored.getFamilyId());
            if (stored.getRevokedAt() == null) {
                stored.setRevokedAt(now);
                repository.save(stored);
            }
            throw ApiException.unauthorized("Refresh token reuse detected");
        }

        User user = stored.getUser();
        String freshRaw = randomToken();
        RefreshToken next = new RefreshToken();
        next.setUser(user);
        next.setTokenHash(hash(freshRaw));
        next.setFamilyId(stored.getFamilyId());
        next.setExpiresAt(now.plus(Duration.ofDays(properties.getJwt().getRefreshTtlDays())));
        applySession(next, session);
        repository.save(next);
        stored.setRotatedAt(now);
        repository.save(stored);
        return new RotationResult(freshRaw, user);
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        repository.findByTokenHash(hash(rawToken)).ifPresent(token -> {
            token.setRevokedAt(Instant.now());
            repository.save(token);
        });
    }

    @Transactional
    public void revokeAllForUser(UUID userId) {
        Instant now = Instant.now();
        for (RefreshToken token : repository.findByUserId(userId)) {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(now);
            }
        }
    }

    private void applySession(RefreshToken entity, SessionInfo session) {
        if (session == null) {
            return;
        }
        entity.setDevice(session.device());
        entity.setUserAgent(session.userAgent());
        entity.setIp(session.ip());
    }

    private void revokeFamily(UUID familyId) {
        Instant now = Instant.now();
        for (RefreshToken token : repository.findByFamilyId(familyId)) {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(now);
            }
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
