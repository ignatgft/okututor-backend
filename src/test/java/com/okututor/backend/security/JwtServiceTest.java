package com.okututor.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.okututor.backend.common.config.AppProperties;
import com.okututor.backend.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private AppProperties propsWithSecret(String secret) {
        AppProperties props = new AppProperties();
        props.getJwt().setSecret(secret);
        props.getJwt().setAccessTtlMinutes(15);
        return props;
    }

    @Test
    void accessTokenRoundTripKeepsSubjectRoleAndEmail() {
        JwtService service = new JwtService(propsWithSecret("a".repeat(48)));
        UUID userId = UUID.randomUUID();

        String token = service.generateAccessToken(userId, "user@test.com", Role.TUTOR);
        Claims claims = service.parse(token);

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("email", String.class)).isEqualTo("user@test.com");
        assertThat(claims.get("role", String.class)).isEqualTo("TUTOR");
    }

    @Test
    void expiredTokenIsRejectedAsExpired() {
        AppProperties props = propsWithSecret("b".repeat(48));
        props.getJwt().setAccessTtlMinutes(-1); // уже истёк
        JwtService service = new JwtService(props);

        String token = service.generateAccessToken(UUID.randomUUID(), "x@test.com", Role.STUDENT);

        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tokenSignedWithDifferentKeyIsRejected() {
        JwtService signer = new JwtService(propsWithSecret("c".repeat(48)));
        JwtService verifier = new JwtService(propsWithSecret("d".repeat(48)));

        String token = signer.generateAccessToken(UUID.randomUUID(), "x@test.com", Role.STUDENT);

        assertThatThrownBy(() -> verifier.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void shortSecretIsRejectedAtStartup() {
        assertThatThrownBy(() -> new JwtService(propsWithSecret("short")))
                .isInstanceOf(io.jsonwebtoken.security.WeakKeyException.class);
    }

    @Test
    void instantNowIsAfterEpoch() {
        assertThat(Instant.now()).isAfter(Instant.EPOCH);
    }
}
