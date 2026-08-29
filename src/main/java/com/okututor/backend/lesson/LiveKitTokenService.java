package com.okututor.backend.lesson;

import com.okututor.backend.common.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * access-токены LiveKit — обычные JWT, подписанные API-секретом проекта, с
 * с claim-ом `video` grant (см. доки LiveKit Server). Пишем вручную, чтобы
 * бэкенду не нужен тяжёлый protobuf SDK.
 *
 * контракт ответа для PgLesson: { server_url: "wss://..", token: ".." }.
 */
@Service
public class LiveKitTokenService {

    public record MeetingToken(String server_url, String token, String room_name) {}

    private final SecretKey secretKey;
    private final String apiKey;
    private final String wsUrl;
    private final Duration ttl;

    public LiveKitTokenService(AppProperties properties) {
        this.secretKey = Keys.hmacShaKeyFor(properties.getLivekit().getApiSecret().getBytes(StandardCharsets.UTF_8));
        this.apiKey = properties.getLivekit().getApiKey();
        this.wsUrl = properties.getLivekit().getWsUrl();
        this.ttl = Duration.ofMinutes(properties.getLivekit().getTokenTtlMinutes());
    }

    public MeetingToken issue(UUID bookingId, UUID userId, String displayName) {
        String room = roomName(bookingId);
        Instant now = Instant.now();
        Map<String, Object> videoGrants = Map.of(
                "room", room,
                "roomJoin", true,
                "canPublish", true,
                "canSubscribe", true,
                "canPublishData", true);

        String jwt = Jwts.builder()
                .issuer(apiKey)
                .subject(userId.toString())
                .id(userId.toString())
                .claim("name", displayName == null ? userId.toString() : displayName)
                .claim("video", videoGrants)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(secretKey)
                .compact();

        return new MeetingToken(wsUrl, jwt, room);
    }

    /** разбирает claims наших токенов — нужно тестам и опциональным проверкам. */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();
    }

    static String roomName(UUID bookingId) {
        return "booking-" + bookingId;
    }
}
