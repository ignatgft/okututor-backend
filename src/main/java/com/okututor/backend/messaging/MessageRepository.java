package com.okututor.backend.messaging;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    List<Message> findByConversation_IdOrderByCreatedAtAsc(UUID conversationId);

    @Query("select count(m) from Message m where m.conversation.id in "
            + "(select c.id from Conversation c where c.user1.id = :userId or c.user2.id = :userId) "
            + "and m.sender.id <> :userId and m.readAt is null")
    long countUnreadAcrossConversations(@Param("userId") UUID userId);

    long countByConversation_IdAndSender_IdNotAndReadAtIsNull(UUID conversationId, UUID senderId);

    @Query("select count(m) from Message m where m.conversation.id = :conversationId "
            + "and m.sender.id <> :viewerId and m.readAt is null")
    long countUnread(@Param("conversationId") UUID conversationId, @Param("viewerId") UUID viewerId);
}
