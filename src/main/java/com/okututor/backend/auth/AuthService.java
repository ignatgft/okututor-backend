package com.okututor.backend.auth;

import com.okututor.backend.auth.dto.AuthTokensResponse;
import com.okututor.backend.auth.dto.CodeSentResponse;
import com.okututor.backend.auth.dto.LoginRequest;
import com.okututor.backend.auth.dto.RegisterRequest;
import com.okututor.backend.auth.dto.StatusResponse;
import com.okututor.backend.auth.dto.VerifiedEmailResponse;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.FieldValidationException;
import com.okututor.backend.common.ratelimit.RateLimitService;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserMapper;
import com.okututor.backend.user.UserRepository;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * регистрация и вход по паролю. Верификация email, восстановление пароля
 * и работа с токенами вынесены в EmailVerificationService,
 * PasswordResetService и RefreshTokenRotationService; оставленные ниже
 * методы-фасады устарели и существуют, чтобы не ломать контроллеры.
 */
@Service
public class AuthService {

    public sealed interface LoginResult {
        record Success(AuthTokensResponse tokens) implements LoginResult {}
        record EmailNotVerified(String email) implements LoginResult {}
    }

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailCodeService emailCodeService;
    private final RateLimitService rateLimitService;
    private final RefreshTokenRotationService tokenRotation;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       EmailCodeService emailCodeService,
                       RateLimitService rateLimitService,
                       RefreshTokenRotationService tokenRotation,
                       EmailVerificationService emailVerificationService,
                       PasswordResetService passwordResetService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailCodeService = emailCodeService;
        this.rateLimitService = rateLimitService;
        this.tokenRotation = tokenRotation;
        this.emailVerificationService = emailVerificationService;
        this.passwordResetService = passwordResetService;
    }

    @Transactional
    public StatusResponse register(RegisterRequest request, String clientIp) {
        rateLimitService.checkRegister(clientIp);

        if (!safeEquals(request.password(), request.repeat_password())) {
            throw new FieldValidationException(Map.of("repeat_password", "Passwords do not match"));
        }
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw ApiException.conflict("An account with this email already exists");
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        String[] names = User.splitFullName(request.full_name());
        user.setFirstName(names[0]);
        user.setLastName(names[1]);
        user.setRole(request.roleOrDefault());
        user.setVerified(false);
        userRepository.save(user);

        emailCodeService.issue(user, email, EmailCodePurpose.EMAIL_VERIFY, null);
        return StatusResponse.emailVerificationRequired(email);
    }

    @Transactional
    public LoginResult login(LoginRequest request, String clientIp) {
        return login(request, clientIp, SessionInfo.empty());
    }

    @Transactional
    public LoginResult login(LoginRequest request, String clientIp, SessionInfo session) {
        rateLimitService.checkLogin(clientIp);

        String email = normalizeEmail(request.email());
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            log.warn("SECURITY login_failed email={} ip={} reason=unknown_user", sanitize(email), clientIp);
            throw ApiException.unauthorized("Invalid email or password");
        }

        if (user.isBlocked()) {
            log.warn("SECURITY login_blocked email={} ip={}", sanitize(email), clientIp);
            throw ApiException.forbidden("Account is blocked. Contact support.");
        }
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("SECURITY login_failed email={} ip={} reason=bad_password", sanitize(email), clientIp);
            throw ApiException.unauthorized("Invalid email or password");
        }
        if (!user.isVerified()) {
            log.info("SECURITY login_email_not_verified email={} ip={}", sanitize(email), clientIp);
            return new LoginResult.EmailNotVerified(user.getEmail());
        }
        return new LoginResult.Success(buildTokenPair(user, session));
    }

    @Transactional
    public StatusResponse changeEmail(User current, String newEmail) {
        String target = newEmail == null ? "" : newEmail.trim().toLowerCase(Locale.ROOT);
        if (target.isEmpty()) {
            throw new FieldValidationException(Map.of("email", "Email is required"));
        }
        if (userRepository.existsByEmail(target)) {
            throw ApiException.conflict("This email is already in use");
        }
        emailCodeService.issue(current, current.getEmail(), EmailCodePurpose.EMAIL_CHANGE, target);
        log.info("SECURITY change_email_request user_id={} from={} to={}",
                current.getId(), sanitize(current.getEmail()), sanitize(target));
        return StatusResponse.verificationCodeSent(target);
    }

    // ---------- устаревшие фасады (делегируют выделенным сервисам) ----------

    /** @use {@link RefreshTokenRotationService#refresh} */
    @Deprecated(forRemoval = true)
    @Transactional
    public AuthTokensResponse refresh(String refreshToken, SessionInfo session) {
        return tokenRotation.refresh(refreshToken, session);
    }

    /** @use {@link RefreshTokenRotationService#logout} */
    @Deprecated(forRemoval = true)
    @Transactional
    public void logout(String refreshToken) {
        tokenRotation.logout(refreshToken);
    }

    /** @use {@link RefreshTokenRotationService#logoutAll} */
    @Deprecated(forRemoval = true)
    @Transactional
    public void logoutAll(java.util.UUID userId) {
        tokenRotation.logoutAll(userId);
    }

    /** @use {@link EmailVerificationService#verifyEmail} */
    @Deprecated(forRemoval = true)
    @Transactional
    public VerifiedEmailResponse verifyEmail(String email, String code) {
        return emailVerificationService.verifyEmail(email, code);
    }

    /** @use {@link EmailVerificationService#resendVerification} */
    @Deprecated(forRemoval = true)
    @Transactional
    public CodeSentResponse resendVerification(String email) {
        return emailVerificationService.resendVerification(email);
    }

    /** @use {@link PasswordResetService#forgotPassword} */
    @Deprecated(forRemoval = true)
    @Transactional
    public StatusResponse forgotPassword(String email) {
        return passwordResetService.forgotPassword(email);
    }

    /** @use {@link PasswordResetService#verifyResetCode} */
    @Deprecated(forRemoval = true)
    @Transactional
    public StatusResponse verifyResetCode(String email, String code) {
        return passwordResetService.verifyResetCode(email, code);
    }

    /** @use {@link PasswordResetService#resetPassword} */
    @Deprecated(forRemoval = true)
    @Transactional
    public StatusResponse resetPassword(String email, String code, String newPassword) {
        return passwordResetService.resetPassword(email, code, newPassword);
    }

    /** @use {@link RefreshTokenRotationService#buildTokenPair(User)} */
    @Deprecated(forRemoval = true)
    public AuthTokensResponse buildTokenPair(User user) {
        return tokenRotation.buildTokenPair(user);
    }

    /** @use {@link RefreshTokenRotationService#buildTokenPair(User, SessionInfo)} */
    @Deprecated(forRemoval = true)
    public AuthTokensResponse buildTokenPair(User user, SessionInfo session) {
        return tokenRotation.buildTokenPair(user, session);
    }

    static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** обрезает и маскирует email для логов, чтобы не светить полный адрес. */
    static String sanitize(String email) {
        if (email == null || email.isBlank()) {
            return "<empty>";
        }
        String e = email.trim();
        int at = e.indexOf('@');
        if (at <= 1) {
            return e;
        }
        String local = e.substring(0, at);
        String visible = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
        return visible + "***@" + e.substring(at + 1);
    }

    private static boolean safeEquals(String a, String b) {
        return a != null && a.equals(b);
    }
}
