package com.okututor.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmailCodeServiceTest {

    private EmailCodeRepository repository;
    private EmailCodeAttemptPersister attemptPersister;
    private AppMailSender mailSender;
    private EmailCodeService service;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(EmailCodeRepository.class);
        attemptPersister = mock(EmailCodeAttemptPersister.class);
        mailSender = mock(AppMailSender.class);
        service = new EmailCodeService(repository, attemptPersister, mailSender);
        user = new User();
        user.setId(java.util.UUID.randomUUID());
        user.setEmail("user@test.com");
        user.setRole(Role.STUDENT);
    }

    @Test
    void issueStoresHashedCodeAndInvalidatesPreviousOne() {
        EmailCode previous = activeCode();
        when(repository.findLatestActive("user@test.com", EmailCodePurpose.EMAIL_VERIFY))
                .thenReturn(Optional.of(previous));

        service.issue(user, "User@Test.com", EmailCodePurpose.EMAIL_VERIFY, null);

        verify(mailSender).sendVerificationCode(any(), any(), any());
        assertThat(previous.getConsumedAt()).isNotNull();
    }

    @Test
    void correctCodeIsConsumed() {
        String code = "123456";
        EmailCode stored = activeCodeWithRaw(code);
        when(repository.findLatestActive("user@test.com", EmailCodePurpose.PASSWORD_RESET))
                .thenReturn(Optional.of(stored));

        assertThatCode(() -> service.verify("user@test.com", EmailCodePurpose.PASSWORD_RESET, code))
                .doesNotThrowAnyException();
        assertThat(stored.isConsumed()).isTrue();
    }

    @Test
    void wrongCodeThrowsInvalidAndCountsAttempt() {
        EmailCode stored = activeCodeWithRaw("123456");
        when(repository.findLatestActive("user@test.com", EmailCodePurpose.PASSWORD_RESET))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.verify("user@test.com", EmailCodePurpose.PASSWORD_RESET, "000000"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid");

        assertThat(stored.getAttempts()).isEqualTo(1);
        verify(attemptPersister).persist(stored);
    }

    @Test
    void expiredCodeThrowsExpired() {
        EmailCode stored = activeCodeWithRaw("123456");
        stored.setExpiresAt(Instant.now().minus(Duration.ofSeconds(5)));
        when(repository.findLatestActive("user@test.com", EmailCodePurpose.PASSWORD_RESET))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.verify("user@test.com", EmailCodePurpose.PASSWORD_RESET, "123456"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired")
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(com.okututor.backend.common.error.ErrorCodes.VERIFICATION_CODE_EXPIRED);
    }

    @Test
    void fifthFailedAttemptLocksTheCode() {
        String raw = "123456";
        EmailCode stored = activeCodeWithRaw(raw);
        when(repository.findLatestActive("user@test.com", EmailCodePurpose.EMAIL_VERIFY))
                .thenReturn(Optional.of(stored));

        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                service.verify("user@test.com", EmailCodePurpose.EMAIL_VERIFY, "000001");
            } catch (ApiException ignored) {
                // ожидаем INVALID_CODE
            }
        }
        assertThat(stored.getAttempts()).isEqualTo(4);

        assertThatThrownBy(() -> service.verify("user@test.com", EmailCodePurpose.EMAIL_VERIFY, "999999"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Too many attempts");
    }

    @Test
    void noActiveCodeAtAllThrowsInvalid() {
        when(repository.findLatestActive(any(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.verify("user@test.com", EmailCodePurpose.EMAIL_VERIFY, "123456"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Invalid");
    }

    private EmailCode activeCode() {
        EmailCode code = new EmailCode();
        code.setEmail("user@test.com");
        code.setUser(user);
        code.setPurpose(EmailCodePurpose.EMAIL_VERIFY);
        code.setCodeHash(RefreshTokenService.hash("000000"));
        code.setExpiresAt(Instant.now().plus(Duration.ofMinutes(10)));
        return code;
    }

    private EmailCode activeCodeWithRaw(String raw) {
        EmailCode code = activeCode();
        code.setCodeHash(RefreshTokenService.hash(raw));
        return code;
    }

    @Test
    void whitespaceInSubmittedCodeIsTrimmed() {
        String code = "654321";
        EmailCode stored = activeCodeWithRaw(code);
        when(repository.findLatestActive("user@test.com", EmailCodePurpose.EMAIL_VERIFY))
                .thenReturn(Optional.of(stored));

        assertThatCode(() -> service.verify("user@test.com", EmailCodePurpose.EMAIL_VERIFY, " 654321 "))
                .doesNotThrowAnyException();
    }

    @SuppressWarnings("unused")
    private void unusedHelper() {
        assertThat(true).isTrue();
    }
}
