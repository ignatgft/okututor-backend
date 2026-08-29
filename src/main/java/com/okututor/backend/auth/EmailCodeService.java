package com.okututor.backend.auth;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.user.User;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * шестизначные одноразовые коды для подтверждения email / сброса пароля / смены email.
 * код живёт 10 минут; максимум 5 попыток ввода на код.
 */
@Service
public class EmailCodeService {

    public static final int CODE_TTL_SECONDS = 600;
    public static final int RESEND_AVAILABLE_IN_SECONDS = 60;
    private static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailCodeRepository repository;
    private final EmailCodeAttemptPersister attemptPersister;
    private final AppMailSender mailSender;

    public EmailCodeService(EmailCodeRepository repository,
                            EmailCodeAttemptPersister attemptPersister,
                            AppMailSender mailSender) {
        this.repository = repository;
        this.attemptPersister = attemptPersister;
        this.mailSender = mailSender;
    }

    /** создаёт новый код для адреса/цели (предыдущие инвалидируются). */
    @Transactional
    public void issue(User userOrNull, String email, EmailCodePurpose purpose, String newEmailOrNull) {
        repository.findLatestActive(email.toLowerCase(), purpose)
                .ifPresent(code -> code.setConsumedAt(Instant.now()));

        String raw = "%06d".formatted(RANDOM.nextInt(1_000_000));
        EmailCode entity = new EmailCode();
        entity.setUser(userOrNull);
        entity.setEmail(email);
        entity.setNewEmail(newEmailOrNull);
        entity.setPurpose(purpose);
        entity.setCodeHash(RefreshTokenService.hash(raw));
        entity.setExpiresAt(Instant.now().plus(Duration.ofSeconds(CODE_TTL_SECONDS)));
        repository.save(entity);

        mailSender.sendVerificationCode(email.toLowerCase(), raw, purpose.name());
    }

    /**
     * проверяет и погашает последний активный код. Кидает INVALID_CODE /
     * VERIFICATION_CODE_EXPIRED / TOO_MANY_ATTEMPTS по контракту фронта.
     * Неудачная попытка инкремента коммитится в независимой транзакции
     * ({@link EmailCodeAttemptPersister}), поэтому счётчик не теряется при rollback.
     */
    public EmailCode verify(String email, EmailCodePurpose purpose, String code) {
        EmailCode stored = repository.findLatestActive(email.toLowerCase(), purpose)
                .orElseThrow(() -> ApiException.invalidCode("Invalid verification code"));

        if (stored.isExpired(Instant.now())) {
            throw ApiException.codeExpired("Verification code expired");
        }
        if (stored.getAttempts() >= MAX_ATTEMPTS) {
            throw ApiException.tooManyAttempts("Too many attempts. Request a new code.");
        }

        if (!RefreshTokenService.hash(normalize(code)).equals(stored.getCodeHash())) {
            stored.setAttempts(stored.getAttempts() + 1);
            attemptPersister.persist(stored); // фиксируем попытку до throw (REQUIRES_NEW)
            throw stored.getAttempts() >= MAX_ATTEMPTS
                    ? ApiException.tooManyAttempts("Too many attempts. Request a new code.")
                    : ApiException.invalidCode("Invalid verification code");
        }

        stored.setConsumedAt(Instant.now());
        repository.save(stored);
        return stored;
    }

    private static String normalize(String code) {
        return code == null ? "" : code.trim();
    }
}
