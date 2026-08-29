package com.okututor.backend.support;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupportTicketMessageRepository extends JpaRepository<SupportTicketMessage, UUID> {

    @Query(value = """
            select m from SupportTicketMessage m
            join fetch m.sender
            where m.ticket.number = :ticketNumber
            order by m.createdAt asc
            """,
            countQuery = "select count(m) from SupportTicketMessage m where m.ticket.number = :ticketNumber")
    Page<SupportTicketMessage> findByTicketNumberOrderByCreatedAtAsc(@Param("ticketNumber") Long ticketNumber,
                                                                     Pageable pageable);
}
