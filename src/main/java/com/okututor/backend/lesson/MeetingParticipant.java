package com.okututor.backend.lesson;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * факт участия в LiveKit-встрече (идентичность = subject JWT-токена = userId).
 * Наполняется вебхуками participant_joined / participant_left; используется
 * для проверки факта посещения (право на отзыв).
 */
@Entity
@Table(name = "meeting_participants")
public class MeetingParticipant {

    @Id
    private UUID id;

    @Column(name = "meeting_session_id", nullable = false)
    private UUID meetingSessionId;

    @Column(nullable = false, length = 64)
    private String identity;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (joinedAt == null) {
            joinedAt = Instant.now();
        }
    }

    public UUID getId() { return id; }
    public UUID getMeetingSessionId() { return meetingSessionId; }
    public void setMeetingSessionId(UUID meetingSessionId) { this.meetingSessionId = meetingSessionId; }
    public String getIdentity() { return identity; }
    public void setIdentity(String identity) { this.identity = identity; }
    public Instant getJoinedAt() { return joinedAt; }
    public void setJoinedAt(Instant joinedAt) { this.joinedAt = joinedAt; }
    public Instant getLeftAt() { return leftAt; }
    public void setLeftAt(Instant leftAt) { this.leftAt = leftAt; }
}
