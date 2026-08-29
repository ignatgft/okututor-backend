package com.okututor.backend.support;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupportTicketRepository
        extends JpaRepository<SupportTicket, UUID>, JpaSpecificationExecutor<SupportTicket> {

    @Query(value = """
            select t from SupportTicket t
            join fetch t.author
            left join fetch t.assignedAdmin
            where t.author.id = :authorId
            order by t.updatedAt desc
            """,
            countQuery = "select count(t) from SupportTicket t where t.author.id = :authorId")
    Page<SupportTicket> findByAuthorIdOrderByUpdatedAtDesc(@Param("authorId") UUID authorId, Pageable pageable);

    Optional<SupportTicket> findByNumber(Long number);

    @Query(value = "SELECT nextval('support_ticket_number_seq')", nativeQuery = true)
    Long nextNumber();

    /** атомарные инкременты вместо read-modify-write в памяти (lost updates). */
    @Modifying
    @Query("update SupportTicket t set t.adminUnreadCount = t.adminUnreadCount + 1 where t.id = :id")
    int incrementAdminUnread(@Param("id") UUID id);

    @Modifying
    @Query("update SupportTicket t set t.userUnreadCount = t.userUnreadCount + 1 where t.id = :id")
    int incrementUserUnread(@Param("id") UUID id);
}
