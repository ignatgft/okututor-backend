package com.okututor.backend.support;

import com.okututor.backend.media.MessageAttachment;
import com.okututor.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "support_ticket_messages")
public class SupportTicketMessage {

    public enum Type { USER_VISIBLE, INTERNAL_NOTE }

    @Id
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private SupportTicket ticket;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    /** денормализовано для быстрого рендера (STUDENT/TUTOR/ADMIN/SUPER_ADMIN) */
    @Column(name = "sender_role", nullable = false, length = 20)
    private String senderRole;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attachment_id")
    private MessageAttachment attachment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type = Type.USER_VISIBLE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = Instant.now();
    }

    // --- геттеры/сеттеры ---

    public UUID getId() { return id; }
    public SupportTicket getTicket() { return ticket; }
    public void setTicket(SupportTicket ticket) { this.ticket = ticket; }
    public User getSender() { return sender; }
    public void setSender(User sender) { this.sender = sender; }
    public UUID getSenderId() { return getSender() != null ? getSender().getId() : null; }
    public void setSenderRole(String senderRole) { this.senderRole = senderRole; }
    public String getSenderRole() { return senderRole; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public MessageAttachment getAttachment() { return attachment; }
    public void setAttachment(MessageAttachment attachment) { this.attachment = attachment; }
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public Instant getCreatedAt() { return createdAt; }
}
