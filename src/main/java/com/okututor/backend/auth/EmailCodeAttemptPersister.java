package com.okututor.backend.auth;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Отдельный бин для фиксации неудачной попытки ввода кода.
 * REQUIRES_NEW гарантирует, что счётчик brute-force закоммитится,
 * даже если вызывающая транзакция откатится из-за бросаемого исключения
 * (self-invocation через this обошёл бы прокси, поэтому — отдельный класс).
 */
@Component
public class EmailCodeAttemptPersister {

    private final EmailCodeRepository repository;

    public EmailCodeAttemptPersister(EmailCodeRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(EmailCode code) {
        repository.save(code);
    }
}
