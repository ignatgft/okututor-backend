package com.okututor.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.okututor.backend.common.config.AppProperties;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RefreshTokenServiceTest {

    private RefreshTokenRepository repository;
    private RefreshTokenService service;
    private AppProperties properties;

    @BeforeEach
    void setUp() {
        repository = mock(RefreshTokenRepository.class);
        properties = new AppProperties();
        properties.getJwt().setSecret("x".repeat(48));
        service = new RefreshTokenService(repository, properties);
    }

    private User user() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("user@test.com");
        user.setRole(Role.STUDENT);
        return user;
    }

    @Test
    void issueStoresHashedTokenAndReturnsRaw() {
        User user = user();
        String raw = service.issueFor(user);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTokenHash()).isEqualTo(RefreshTokenService.hash(raw));
        assertThat(captor.getValue().getUser()).isEqualTo(user);
    }

    @Test
    void rotateValidTokenIssuesNewSiblingInSameFamilyAndMarksRotated() {
        String raw = "a".repeat(64);
        RefreshToken stored = new RefreshToken();
        User owner = user();
        UUID family = UUID.randomUUID();
        stored.setUser(owner);
        stored.setTokenHash(RefreshTokenService.hash(raw));
        stored.setFamilyId(family);
        stored.setExpiresAt(Instant.now().plusSeconds(3600));
        when(repository.findByTokenHash(RefreshTokenService.hash(raw))).thenReturn(Optional.of(stored));

        RefreshTokenService.RotationResult result = service.rotate(raw);

        assertThat(result.refreshToken()).isNotEqualTo(raw);
        assertThat(result.user()).isEqualTo(owner);
        assertThat(stored.getRotatedAt()).isNotNull();

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues().stream().anyMatch(t ->
                t != stored && t.getFamilyId().equals(family))).isTrue();
    }

    @Test
    void reuseBeyondGraceRevokesWholeFamily() {
        String raw = "b".repeat(64);
        RefreshToken stored = new RefreshToken();
        stored.setUser(user());
        stored.setTokenHash(RefreshTokenService.hash(raw));
        stored.setFamilyId(UUID.randomUUID());
        stored.setExpiresAt(Instant.now().plusSeconds(3600));
        stored.setRotatedAt(Instant.now().minusSeconds(600)); // ротирован давно
        when(repository.findByTokenHash(RefreshTokenService.hash(raw))).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.rotate(raw))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("reuse");

        verify(repository, atLeastOnce()).save(argThat(t -> t.getRevokedAt() != null || t == stored));
    }

    @Test
    void revokedTokenIsRejected() {
        String raw = "c".repeat(64);
        RefreshToken stored = new RefreshToken();
        stored.setUser(user());
        stored.setTokenHash(RefreshTokenService.hash(raw));
        stored.setFamilyId(UUID.randomUUID());
        stored.setExpiresAt(Instant.now().plusSeconds(3600));
        stored.setRevokedAt(Instant.now().minusSeconds(60));
        when(repository.findByTokenHash(RefreshTokenService.hash(raw))).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.rotate(raw))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void expiredTokenIsRejected() {
        String raw = "d".repeat(64);
        RefreshToken stored = new RefreshToken();
        stored.setUser(user());
        stored.setTokenHash(RefreshTokenService.hash(raw));
        stored.setFamilyId(UUID.randomUUID());
        stored.setExpiresAt(Instant.now().minusSeconds(10));
        when(repository.findByTokenHash(RefreshTokenService.hash(raw))).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.rotate(raw))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void unknownTokenIsRejectedWithoutFamilyRevocation() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.rotate("unknown"))
                .isInstanceOf(ApiException.class);
        verify(repository, never()).findByFamilyId(any());
    }

    @Test
    void issuedSessionStoresDeviceUserAgentAndIp() {
        User owner = user();
        var session = new SessionInfo("Mozilla/5.0 TestBrowser", "Mozilla/5.0 TestBrowser", "203.0.113.7");

        service.issueFor(owner, session);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(captor.capture());
        RefreshToken saved = captor.getValue();
        assertThat(saved.getDevice()).isEqualTo("Mozilla/5.0 TestBrowser");
        assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0 TestBrowser");
        assertThat(saved.getIp()).isEqualTo("203.0.113.7");
    }

    @Test
    void rotationCarriesFreshSessionMetadataToNewToken() {
        String raw = "e".repeat(64);
        RefreshToken stored = new RefreshToken();
        stored.setUser(user());
        stored.setTokenHash(RefreshTokenService.hash(raw));
        stored.setFamilyId(UUID.randomUUID());
        stored.setExpiresAt(Instant.now().plusSeconds(3600));
        when(repository.findByTokenHash(RefreshTokenService.hash(raw))).thenReturn(Optional.of(stored));

        var newSession = new SessionInfo("Mobile-UA", "Mobile-UA", "198.51.100.9");
        service.rotate(raw, newSession);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        RefreshToken next = captor.getAllValues().stream()
                .filter(t -> t != stored && t.getRotatedAt() == null)
                .findFirst().orElseThrow();
        assertThat(next.getDevice()).isEqualTo("Mobile-UA");
        assertThat(next.getIp()).isEqualTo("198.51.100.9");
    }

    private static <T> T argThat(org.mockito.ArgumentMatcher<T> matcher) {
        return org.mockito.ArgumentMatchers.argThat(matcher);
    }

    private static org.mockito.verification.VerificationMode atLeastOnce() {
        return org.mockito.Mockito.atLeastOnce();
    }
}
