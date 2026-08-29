package com.okututor.backend.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByFamilyId(UUID familyId);

    List<RefreshToken> findByUserId(UUID userId);

    @Modifying
    void deleteByUserId(UUID userId);

    long countByFamilyIdAndRevokedAtIsNullAndRotatedAtIsNull(UUID familyId);

    Optional<RefreshToken> findFirstByFamilyIdOrderByCreatedAtDesc(UUID familyId);

    default List<RefreshToken> findActiveByFamily(UUID familyId, Instant now) {
        return findByFamilyId(familyId).stream()
                .filter(t -> t.getRevokedAt() == null && t.getRotatedAt() == null && t.getExpiresAt().isAfter(now))
                .toList();
    }
}
