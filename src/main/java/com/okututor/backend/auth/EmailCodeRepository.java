package com.okututor.backend.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailCodeRepository extends JpaRepository<EmailCode, UUID> {

    Optional<EmailCode> findByEmailAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            String email, EmailCodePurpose purpose);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from EmailCode c where c.email = :email and c.purpose = :purpose "
            + "and c.consumedAt is null order by c.createdAt desc limit 1")
    Optional<EmailCode> lockLatestActive(@Param("email") String email, @Param("purpose") EmailCodePurpose purpose);

    default Optional<EmailCode> findLatestActive(String email, EmailCodePurpose purpose) {
        return findByEmailAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(email.toLowerCase(), purpose);
    }
}
