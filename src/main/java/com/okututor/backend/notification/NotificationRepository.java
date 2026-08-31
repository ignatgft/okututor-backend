package com.okututor.backend.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<Notification> findByUserIdAndReadFalse(UUID userId);

    List<Notification> findByUserIdAndReadFalse(UUID userId, Pageable pageable);

    long countByUserIdAndReadFalse(UUID userId);

    long deleteByUserIdAndReadTrue(UUID userId);

    List<Notification> findByEntityTypeAndEntityIdAndType(String entityType, String entityId, String type);

    /** существует ли почтовое событие (напр. напоминание) с конкретным окном в payload. */
    @Query(value = """
            select case when count(n) > 0 then true else false end
            from notifications n
            where n.entity_type = :entityType and n.entity_id = :entityId and n.type = :type
              and coalesce(n.payload->>'window', '') = :window
            """, nativeQuery = true)
    boolean existsReminder(@Param("entityType") String entityType, @Param("entityId") String entityId,
                           @Param("type") String type, @Param("window") String window);

    /** batch-апдейт вместо загрузки всех непрочитанных в память. */
    @Modifying
    @Query("""
            update Notification n
            set n.read = true, n.readAt = :now
            where n.user.id = :userId and n.read = false
            """)
    int markAllReadForUser(@Param("userId") UUID userId, @Param("now") java.time.Instant now);
}
