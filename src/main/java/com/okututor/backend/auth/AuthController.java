package com.okututor.backend.auth;

import com.okututor.backend.auth.dto.AuthTokensResponse;
import com.okututor.backend.auth.dto.CodeSentResponse;
import com.okututor.backend.auth.dto.EmailNotVerifiedResponse;
import com.okututor.backend.auth.dto.LoginRequest;
import com.okututor.backend.auth.dto.RegisterRequest;
import com.okututor.backend.auth.dto.StatusResponse;
import com.okututor.backend.auth.dto.VerifiedEmailResponse;
import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.user.UserMapper;
import com.okututor.backend.user.UserRepository;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record LogoutRequest(String refresh_token) {}
    public record RefreshRequest(String refresh_token) {}
    public record VerifyEmailRequest(String email, String code) {}
    public record ResendVerificationRequest(String email) {}
    public record ForgotPasswordRequest(String email) {}
    public record ResetPasswordRequest(String email, String code, String password) {}
    public record ChangeEmailRequest(String email) {}

    private final AuthService authService;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final ClientIpResolver clientIpResolver;

    public AuthController(AuthService authService,
                          UserRepository userRepository,
                          UserMapper userMapper,
                          ClientIpResolver clientIpResolver) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.userMapper = userMapper;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        AuthService.LoginResult result = authService.login(request,
                clientIpResolver.resolve(http), SessionInfo.of(http, clientIpResolver));
        if (result instanceof AuthService.LoginResult.EmailNotVerified unverified) {
            // специально HTTP 200: фронт проверяет status в теле, а не HTTP-код
            return ResponseEntity.ok(EmailNotVerifiedResponse.of(unverified.email()));
        }
        return ResponseEntity.ok(((AuthService.LoginResult.Success) result).tokens());
    }

    @PostMapping("/register")
    public StatusResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        return authService.register(request, clientIpResolver.resolve(http));
    }

    @PostMapping("/refresh")
    public AuthTokensResponse refresh(@RequestBody RefreshRequest request, HttpServletRequest http) {
        return authService.refresh(request.refresh_token(), SessionInfo.of(http, clientIpResolver));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) LogoutRequest request) {
        authService.logout(request == null ? null : request.refresh_token());
        return ResponseEntity.noContent().build();
    }

    /** Выход со всех устройств (текущая сессия тоже ревокается). */
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal UserPrincipal principal) {
        AuthController.requireAuth(principal);
        authService.logoutAll(principal.id());
        return ResponseEntity.noContent().build();
    }

    /** облегчённый эндпоинт восстановления сессии, используется интерцептором. */
    @GetMapping("/me")
    public com.okututor.backend.user.dto.UserResponse me(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        return userRepository.findById(principal.id())
                .map(userMapper::toResponse)
                .orElseThrow(() -> ApiException.unauthorized("User no longer exists"));
    }

    @PostMapping("/verify-email")
    public VerifiedEmailResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        return authService.verifyEmail(request.email(), request.code());
    }

    @PostMapping("/resend-verification")
    public CodeSentResponse resendVerification(@RequestBody ResendVerificationRequest request) {
        return authService.resendVerification(request.email());
    }

    @PostMapping("/forgot-password")
    public StatusResponse forgotPassword(@RequestBody ForgotPasswordRequest request) {
        return authService.forgotPassword(request.email());
    }

    @PostMapping("/verify-reset-code")
    public StatusResponse verifyResetCode(@RequestBody VerifyEmailRequest request) {
        return authService.verifyResetCode(request.email(), request.code());
    }

    @PostMapping("/reset-password")
    public StatusResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return authService.resetPassword(request.email(), request.code(), request.password());
    }

    @PostMapping("/change-email")
    public StatusResponse changeEmail(@AuthenticationPrincipal UserPrincipal principal,
                                      @RequestBody ChangeEmailRequest request) {
        requireAuth(principal);
        return authService.changeEmail(
                userRepository.findById(principal.id()).orElseThrow(() -> ApiException.unauthorized("Unknown user")),
                request.email());
    }

    static void requireAuth(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("Authentication required");
        }
    }
}
