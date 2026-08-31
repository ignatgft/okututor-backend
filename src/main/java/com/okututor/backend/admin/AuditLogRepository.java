package com.okututor.backend.admin;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findByOrderByCreatedAtDesc(Pageable pageable);

    /** таймлайн заявки: левый join на actor (может быть null у системных событий). */
    @org.springframework.data.jpa.repository.Query("""
            select a from AuditLog a
            left join fetch a.actor
            where a.targetType = :targetType and a.targetId = :targetId
            order by a.createdAt asc
            """)
    List<AuditLog> findByTargetOrderByCreatedAt(@org.springframework.data.repository.query.Param("targetType") String targetType,
                                                @org.springframework.data.repository.query.Param("targetId") String targetId);
}
