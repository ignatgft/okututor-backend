package com.okututor.backend.messaging;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    @Query("""
            select c from Conversation c
            join fetch c.user1
            join fetch c.user2
            where c.type = com.okututor.backend.messaging.Conversation.Type.DIRECT
              and (c.user1.id = :userId or c.user2.id = :userId)
            order by coalesce(c.lastMessageAt, c.createdAt) desc
            """)
    List<Conversation> findDirectForUser(@Param("userId") UUID userId, Pageable pageable);

    Optional<Conversation> findByTypeAndUser1_IdAndUser2_Id(Conversation.Type type, UUID user1Id, UUID user2Id);
}
