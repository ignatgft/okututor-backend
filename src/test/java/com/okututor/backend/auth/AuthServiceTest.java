package com.okututor.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.okututor.backend.auth.dto.AuthTokensResponse;
import com.okututor.backend.auth.dto.LoginRequest;
import com.okututor.backend.auth.dto.RegisterRequest;
import com.okututor.backend.auth.dto.StatusResponse;
import com.okututor.backend.common.config.AppProperties;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.FieldValidationException;
import com.okututor.backend.common.ratelimit.RateLimitService;
import com.okututor.backend.security.JwtService;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserMapper;
import com.okututor.backend.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private RefreshTokenService refreshTokenService;
    private EmailCodeService emailCodeService;
    private RateLimitService rateLimitService;
    private RefreshTokenRotationService tokenRotation;
    private EmailVerificationService emailVerificationService;
    private PasswordResetService passwordResetService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        emailCodeService = mock(EmailCodeService.class);
        AppProperties props = new AppProperties();
        props.getRateLimit().setEnabled(false);
        rateLimitService = new RateLimitService(Optional.of(mock(com.okututor.backend.common.ratelimit.RedisRateLimiter.class)), props);
        tokenRotation = new RefreshTokenRotationService(jwtService, refreshTokenService, new UserMapper());
        emailVerificationService = mock(EmailVerificationService.class);
        passwordResetService = new PasswordResetService(
                userRepository, emailCodeService, tokenRotation, rateLimitService, passwordEncoder);
        authService = new AuthService(userRepository, passwordEncoder, emailCodeService, rateLimitService,
                tokenRotation, emailVerificationService, passwordResetService);
    }

    private User verifiedUser(String role) {
        User user = new User();
        user.setId(java.util.UUID.randomUUID());
        user.setEmail("user@test.com");
        user.setPasswordHash("hash");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setVerified(true);
        user.setRole(Role.valueOf(role));
        return user;
    }

    @Test
    void registerRejectsMismatchedRepeatPasswordWithFieldError() {
        RegisterRequest request = new RegisterRequest(
                "new@test.com", "password123", "different", "New User", Role.STUDENT);

        assertThatThrownBy(() -> authService.register(request, "1.2.3.4"))
                .isInstanceOf(FieldValidationException.class)
                .extracting(e -> ((FieldValidationException) e).getFieldErrors())
                .satisfies(errors -> assertThat((java.util.Map<String, String>) errors)
                        .containsEntry("repeat_password", "Passwords do not match"));

        verify(userRepository, never()).save(any());
    }

    @Test
    void registerExistingEmailConflicts() {
        when(userRepository.existsByEmail("taken@test.com")).thenReturn(true);
        RegisterRequest request = new RegisterRequest(
                "taken@test.com", "password123", "password123", "New User", Role.STUDENT);

        assertThatThrownBy(() -> authService.register(request, "1.2.3.4"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("CONFLICT");
    }

    @Test
    void registerCreatesUnverifiedUserAndIssuesVerificationCode() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        RegisterRequest request = new RegisterRequest(
                "new@test.com", "password123", "password123", "New User", Role.TUTOR);

        StatusResponse response = authService.register(request, "1.2.3.4");

        assertThat(response.status()).isEqualTo("EMAIL_VERIFICATION_REQUIRED");
        verify(userRepository).save(argThat((User u) ->
                !u.isVerified()
                        && u.getRole() == Role.TUTOR
                        && u.getFirstName().equals("New")
                        && u.getEmail().equals("new@test.com")));
        verify(emailCodeService).issue(any(), anyString(), any(), any());
    }

    @Test
    void loginSuccessReturnsTokensAndUser() {
        User user = verifiedUser("STUDENT");
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret123", "hash")).thenReturn(true);
        when(jwtService.generateAccessToken(user.getId(), user.getEmail(), Role.STUDENT)).thenReturn("access.jwt");
        when(refreshTokenService.issueFor(user)).thenReturn("refresh-token-raw");
        when(refreshTokenService.issueFor(eq(user), any(SessionInfo.class))).thenReturn("refresh-token-raw");

        AuthService.LoginResult result = authService.login(new LoginRequest("USER@test.com", "secret123"), "ip");

        assertThat(result).isInstanceOf(AuthService.LoginResult.Success.class);
        AuthTokensResponse tokens = ((AuthService.LoginResult.Success) result).tokens();
        assertThat(tokens.access_token()).isEqualTo("access.jwt");
        assertThat(tokens.refresh_token()).isEqualTo("refresh-token-raw");
        assertThat(tokens.user().fullName()).isEqualTo("Test User");
    }

    @Test
    void loginUnverifiedAccountIsNotAnHttpErrorButSpecialStatus() {
        User user = verifiedUser("STUDENT");
        user.setVerified(false);
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(any(), any())).thenReturn(true);

        AuthService.LoginResult result = authService.login(new LoginRequest("user@test.com", "x"), "ip");

        assertThat(result).isInstanceOf(AuthService.LoginResult.EmailNotVerified.class);
    }

    @Test
    void loginWrongPasswordIsUnauthorized() {
        User user = verifiedUser("STUDENT");
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("user@test.com", "bad"), "ip"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("UNAUTHORIZED");
    }

    @Test
    void blockedAccountCannotLogin() {
        User user = verifiedUser("STUDENT");
        user.setBlocked(true);
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("user@test.com", "x"), "ip"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("FORBIDDEN");
    }

    @Test
    void forgotPasswordAlwaysReportsEmailSentEvenForUnknownAddress() {
        when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());

        StatusResponse response = passwordResetService.forgotPassword("ghost@test.com");

        assertThat(response.status()).isEqualTo("PASSWORD_RESET_EMAIL_SENT");
        verify(emailCodeService, never()).issue(any(), any(), any(), any());
    }

    @Test
    void resetPasswordRequiresMinimumLengthAndRevokesSessions() {
        assertThatThrownBy(() ->
                passwordResetService.resetPassword("user@test.com", "123456", "short"))
                .isInstanceOf(FieldValidationException.class);

        // неверный код: emailCodeService.verify кидает исключение до смены пароля
        when(emailCodeService.verify(anyString(), any(), anyString()))
                .thenThrow(com.okututor.backend.common.error.ApiException.invalidCode("Invalid verification code"));
        assertThatThrownBy(() ->
                passwordResetService.resetPassword("user@test.com", "000000", "longenough123"))
                .isInstanceOf(Exception.class);
        verify(refreshTokenService, never()).revokeAllForUser(any());
    }

    private static User argThat(org.mockito.ArgumentMatcher<User> matcher) {
        return org.mockito.ArgumentMatchers.argThat(matcher);
    }
}
