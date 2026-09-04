package com.okututor.backend.lesson;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * дедупликация доставленных вебхуков LiveKit: PK = SHA-256(event id + event + room + ts).
 * LiveKit шлёт события с повторами (at-least-once) — повтор игнорируется.
 */
@Entity
@Table(name = "livekit_webhook_events")
public class LiveKitWebhookEvent {

    @Id
    @Column(name = "event_hash", length = 64)
    private String eventHash;

    @Column(name = "room_name", length = 120)
    private String roomName;

    @Column(length = 64)
    private String event;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    public LiveKitWebhookEvent() {
    }

    public LiveKitWebhookEvent(String eventHash, String roomName, String event) {
        this.eventHash = eventHash;
        this.roomName = roomName;
        this.event = event;
        this.receivedAt = Instant.now();
    }

    public String getEventHash() { return eventHash; }
    public String getRoomName() { return roomName; }
    public String getEvent() { return event; }
    public Instant getReceivedAt() { return receivedAt; }
}
