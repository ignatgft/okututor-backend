package com.okututor.backend.lesson;

import com.okututor.backend.common.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * приём вебхуков LiveKit (room_started / room_finished / participant_joined / participant_left).
 *
 * Проверка подлинности — по свежим докам LiveKit Webhooks
 * (https://docs.livekit.io/home/server/webhooks/): запрос несёт Authorization JWT,
 * подписанный HS256 тем же api-secret, что и токены доступа, c claim-ом sha256
 * (base64 SHA-256 хеш тела) и iss = api key. Логика зеркалирует референсный
 * TokenVerifier из livekit server-sdk (validate token -> compare body hash).
 *
 * Идемпотентность: LiveKit повторяет доставку (at-least-once), поэтому каждый
 * event дедуплицируется по SHA-256(body) в livekit_webhook_events — повтор
 * того же события не меняет состояние.
 */
@Service
public class LiveKitWebhookService {

    private static final Logger log = LoggerFactory.getLogger(LiveKitWebhookService.class);

    private final SecretKey secretKey;
    private final String apiKey;
    private final LessonRepository lessonRepository;
    private final MeetingSessionRepository meetingSessionRepository;
    private final MeetingParticipantRepository participantRepository;
    private final LiveKitWebhookEventRepository webhookEventRepository;

    public LiveKitWebhookService(AppProperties properties,
                                 LessonRepository lessonRepository,
                                 MeetingSessionRepository meetingSessionRepository,
                                 MeetingParticipantRepository participantRepository,
                                 LiveKitWebhookEventRepository webhookEventRepository) {
        String rawSecret = properties.getLivekit().getApiSecret();
        if (rawSecret == null || rawSecret.isBlank()) {
            throw new IllegalStateException(
                    "LIVEKIT_API_SECRET is not set — LiveKit webhook endpoint cannot validate signatures");
        }
        this.secretKey = Keys.hmacShaKeyFor(rawSecret.getBytes(StandardCharsets.UTF_8));
        this.apiKey = properties.getLivekit().getApiKey();
        this.lessonRepository = lessonRepository;
        this.meetingSessionRepository = meetingSessionRepository;
        this.participantRepository = participantRepository;
        this.webhookEventRepository = webhookEventRepository;
    }

    /**
     * @return true если подпись валидна (и тело соответствует хешу)
     */
    public boolean verifySignature(String authHeader, String body) {
        if (authHeader == null || authHeader.isBlank() || body == null) {
            return false;
        }
        try {
            Claims claims = Jwts.parser()
                    .requireIssuer(apiKey == null ? "" : apiKey)
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(authHeader)
                    .getPayload();
            String sha256 = claims.get("sha256", String.class);
            if (sha256 == null) {
                log.warn("LiveKit webhook: JWT has no sha256 claim");
                return false;
            }
            byte[] bodyHash = MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(Base64.getDecoder().decode(sha256), bodyHash);
        } catch (io.jsonwebtoken.JwtException e) {
            log.warn("LiveKit webhook: invalid JWT signature: {}", e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            log.warn("LiveKit webhook: malformed sha256 claim: {}", e.getMessage());
            return false;
        } catch (java.security.GeneralSecurityException e) {
            log.error("LiveKit webhook: hash computation failed", e);
            return false;
        }
    }

    @Transactional
    public void handle(String body) {
        com.fasterxml.jackson.databind.JsonNode root = parse(body);
        if (root == null) {
            log.warn("LiveKit webhook: unparsable body");
            return;
        }
        String event = text(root, "event");
        String eventId = text(root, "id");
        String roomName = text(root.path("room"), "name");
        Instant createdAt = root.hasNonNull("createdAt")
                ? Instant.ofEpochSecond(root.get("createdAt").asLong())
                : Instant.now();

        // дедупликация: at-least-once доставка LiveKit + защита от replay той же пары
        if (webhookEventRepository.existsById(eventHash(eventId, event, roomName, createdAt))) {
            log.debug("LiveKit webhook: duplicate event {} ({}) skipped", eventId, event);
            return;
        }
        webhookEventRepository.save(new LiveKitWebhookEvent(
                eventHash(eventId, event, roomName, createdAt), roomName, event));

        switch (event == null ? "" : event) {
            case "room_started" -> onRoomStarted(roomName);
            case "room_finished" -> onRoomFinished(roomName, createdAt);
            case "participant_joined", "participant_left" -> onParticipant(
                    roomName, event, text(root.path("participant"), "identity"), createdAt);
            default -> log.info("LiveKit webhook: unhandled event type '{}' (id={})", event, eventId);
        }
    }

    /** первый участник вошёл в комнату — урок фактически начался. */
    private void onRoomStarted(String roomName) {
        findLessonByRoom(roomName).ifPresent(lesson -> {
            if (lesson.getStatus() == Lesson.Status.SCHEDULED) {
                LessonService.transition(lesson, Lesson.Status.IN_PROGRESS);
                if (lesson.getActualStart() == null) {
                    lesson.setActualStart(Instant.now());
                }
                lessonRepository.save(lesson);
                log.info("LiveKit webhook: lesson {} auto-started (room_started)", lesson.getId());
            }
        });
    }

    /**
     * комната закрыта — фиксируем факт окончания звонка в MeetingSession.
     * Урок НЕ переводится в COMPLETED: это остаётся ручным действием тьютора
     * (домашнее задание/заметки), см. vision.
     */
    private void onRoomFinished(String roomName, Instant at) {
        meetingSessionRepository.findByRoomName(roomName).ifPresent(session -> {
            if (session.getEndedAt() == null) {
                session.setEndedAt(at);
            }
            session.setWebhookEventAt(at);
            meetingSessionRepository.save(session);
        });
    }

    private void onParticipant(String roomName, String event, String identity, Instant at) {
        if (identity == null || identity.isBlank()) {
            log.warn("LiveKit webhook: {} without participant identity (room={})", event, roomName);
            return;
        }
        MeetingSession session = meetingSessionRepository.findByRoomName(roomName).orElse(null);
        if (session == null) {
            log.info("LiveKit webhook: {} for unknown session (room={})", event, roomName);
            return;
        }
        MeetingParticipant participant = participantRepository
                .findByMeetingSessionIdAndIdentity(session.getId(), identity)
                .orElseGet(() -> {
                    MeetingParticipant fresh = new MeetingParticipant();
                    fresh.setMeetingSessionId(session.getId());
                    fresh.setIdentity(identity);
                    return fresh;
                });
        if ("participant_joined".equals(event)) {
            if (participant.getJoinedAt() == null) {
                participant.setJoinedAt(at);
            }
        } else {
            participant.setLeftAt(at);
        }
        participantRepository.save(participant);
        session.setWebhookEventAt(at);
        meetingSessionRepository.save(session);
    }

    private java.util.Optional<Lesson> findLessonByRoom(String roomName) {
        // room = "booking-<uuid>"; lesson ищем по booking
        if (roomName == null || !roomName.startsWith("booking-")) {
            return java.util.Optional.empty();
        }
        try {
            UUID bookingId = UUID.fromString(roomName.substring("booking-".length()));
            return lessonRepository.findByBookingId(bookingId);
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }

    private static String eventHash(String eventId, String event, String room, Instant at) {
        String raw = (eventId != null ? eventId : "")
                + "|" + (event != null ? event : "")
                + "|" + (room != null ? room : "")
                + "|" + (at != null ? at.getEpochSecond() : 0);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode parse(String body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
