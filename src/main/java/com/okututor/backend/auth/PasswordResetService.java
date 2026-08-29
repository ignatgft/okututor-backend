package com.okututor.backend.auth;

import com.okututor.backend.auth.dto.StatusResponse;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.FieldValidationException;
import com.okututor.backend.common.ratelimit.RateLimitService;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserRepository;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** восстановление пароля: forgot → verify кода → reset. Выделено из AuthService. */
@Service
public class PasswordResetService {

    private final UserRepository userRepository;
    private final EmailCodeService emailCodeService;
    private final RefreshTokenRotationService tokenRotation;
    private final RateLimitService rateLimitService;
    private final PasswordEncoder passwordEncoder;

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    public PasswordResetService(UserRepository userRepository,
                                EmailCodeService emailCodeService,
                                RefreshTokenRotationService tokenRotation,
                                RateLimitService rateLimitService,
                                PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.emailCodeService = emailCodeService;
        this.tokenRotation = tokenRotation;
        this.rateLimitService = rateLimitService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public StatusResponse forgotPassword(String email) {
        rateLimitService.checkForgotPassword(email);
        userRepository.findByEmail(normalizeEmail(email))
                .filter(user -> user.getPasswordHash() != null)
                .ifPresent(user -> emailCodeService.issue(user, email, EmailCodePurpose.PASSWORD_RESET, null));
        // анти-перебор: всегда одинаковый ответ
        return new StatusResponse("PASSWORD_RESET_EMAIL_SENT", null);
    }

    @Transactional
    public StatusResponse verifyResetCode(String email, String code) {
        emailCodeService.verify(normalizeEmail(email), EmailCodePurpose.PASSWORD_RESET, code);
        return new StatusResponse("RESET_CODE_VERIFIED", null);
    }

    @Transactional
    public StatusResponse resetPassword(String email, String code, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new FieldValidationException(Map.of("password", "Password must be at least 8 characters"));
        }
        String normalized = normalizeEmail(email);
        emailCodeService.verify(normalized, EmailCodePurpose.PASSWORD_RESET, code);
        User user = userRepository.findByEmail(normalized)
                .orElseThrow(() -> ApiException.invalidCode("Invalid verification code"));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        tokenRotation.revokeAllForUser(user.getId());
        log.info("SECURITY password_reset user_id={} email={}", user.getId(),
                AuthService.sanitize(normalized));
        return new StatusResponse("PASSWORD_RESET", null);
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
