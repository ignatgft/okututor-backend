package com.okututor.backend.support;

import com.okututor.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "support_tickets")
public class SupportTicket {

    public enum Category { TECHNICAL, PAYMENT, COURSE, ACCOUNT, BUG }

    public enum Priority { LOW, NORMAL, HIGH, URGENT }

    public enum Status { OPEN, IN_PROGRESS, WAITING_FOR_USER, RESOLVED, CLOSED }

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private Long number;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User author;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Category category;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Priority priority = Priority.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.OPEN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_admin_id")
    private User assignedAdmin;

    @Column(name = "last_message_preview", columnDefinition = "text")
    private String lastMessagePreview;

    @Column(name = "user_unread_count", nullable = false)
    private int userUnreadCount = 0;

    @Column(name = "admin_unread_count", nullable = false)
    private int adminUnreadCount = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** optimistic locking: защита счётчиков непрочитанного от lost updates */
    @Version
    private Long version;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isAuthor(UUID userId) {
        return getAuthorId() != null && getAuthorId().equals(userId);
    }

    // --- геттеры/сеттеры ---

    public UUID getId() { return id; }
    public Long getNumber() { return number; }
    public void setNumber(Long number) { this.number = number; }
    /** публичный id для фронта: TK-<number>. */
    public String displayId() { return number == null ? null : "TK-" + number; }
    public User getAuthor() { return author; }
    public void setAuthor(User author) { this.author = author; }
    public UUID getAuthorId() { return getAuthor() != null ? getAuthor().getId() : null; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Priority getPriority() { return priority; }
    public void setPriority(Priority priority) { this.priority = priority; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public User getAssignedAdmin() { return assignedAdmin; }
    public void setAssignedAdmin(User assignedAdmin) { this.assignedAdmin = assignedAdmin; }
    public String getLastMessagePreview() { return lastMessagePreview; }
    public void setLastMessagePreview(String v) { this.lastMessagePreview = v; }
    public int getUserUnreadCount() { return userUnreadCount; }
    public void setUserUnreadCount(int v) { this.userUnreadCount = v; }
    public int getAdminUnreadCount() { return adminUnreadCount; }
    public void setAdminUnreadCount(int v) { this.adminUnreadCount = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
