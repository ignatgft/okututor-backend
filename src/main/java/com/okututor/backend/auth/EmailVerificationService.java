package com.okututor.backend.auth;

import com.okututor.backend.auth.dto.CodeSentResponse;
import com.okututor.backend.auth.dto.VerifiedEmailResponse;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.ratelimit.RateLimitService;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * подтверждение владения email и смена email. Выделено из AuthService.
 */
@Service
public class EmailVerificationService {

    private final UserRepository userRepository;
    private final EmailCodeRepository emailCodeRepository;
    private final EmailCodeService emailCodeService;
    private final RefreshTokenRotationService tokenRotation;
    private final RateLimitService rateLimitService;

    public EmailVerificationService(UserRepository userRepository,
                                    EmailCodeRepository emailCodeRepository,
                                    EmailCodeService emailCodeService,
                                    RefreshTokenRotationService tokenRotation,
                                    RateLimitService rateLimitService) {
        this.userRepository = userRepository;
        this.emailCodeRepository = emailCodeRepository;
        this.emailCodeService = emailCodeService;
        this.tokenRotation = tokenRotation;
        this.rateLimitService = rateLimitService;
    }

    /**
     * проверяет код EMAIL_VERIFY; также завершает флоу EMAIL_CHANGE, когда
     * passed address does not belong to any account yet but a change code
     * для него существует код (страница настроек переиспользует тот же verify-email эндпоинт).
     */
    @Transactional
    public VerifiedEmailResponse verifyEmail(String email, String code) {
        String normalized = normalizeEmail(email);

        if (emailCodeRepository.findLatestActive(normalized, EmailCodePurpose.EMAIL_VERIFY).isPresent()) {
            emailCodeService.verify(normalized, EmailCodePurpose.EMAIL_VERIFY, code);
            User user = userRepository.findByEmail(normalized)
                    .orElseThrow(() -> ApiException.invalidCode("Invalid verification code"));
            user.setVerified(true);
            return verifiedResponse(user);
        }

        if (emailCodeRepository.findLatestActive(normalized, EmailCodePurpose.EMAIL_CHANGE).isPresent()) {
            emailCodeService.verify(normalized, EmailCodePurpose.EMAIL_CHANGE, code);
            EmailCode changeCode = emailCodeRepository
                    .findLatestActive(normalized, EmailCodePurpose.EMAIL_CHANGE).orElseThrow();
            User owner = changeCode.getUser();
            if (owner == null || userRepository.existsByEmail(normalized)) {
                throw ApiException.conflict("This email is already in use");
            }
            owner.setEmail(normalized);
            owner.setVerified(true);
            tokenRotation.revokeAllForUser(owner.getId());
            return verifiedResponse(owner);
        }

        throw ApiException.invalidCode("Invalid verification code");
    }

    @Transactional
    public CodeSentResponse resendVerification(String email) {
        rateLimitService.checkResend(email);
        userRepository.findByEmail(normalizeEmail(email)).ifPresent(user ->
                emailCodeService.issue(user, email, EmailCodePurpose.EMAIL_VERIFY, null));
        // анти-перебор: одинаковый ответ для неизвестных email
        return CodeSentResponse.resendVerification();
    }

    private VerifiedEmailResponse verifiedResponse(User user) {
        var tokens = tokenRotation.buildTokenPair(user);
        return new VerifiedEmailResponse("EMAIL_VERIFIED", tokens.access_token(), tokens.refresh_token(),
                tokens.user());
    }

    static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
