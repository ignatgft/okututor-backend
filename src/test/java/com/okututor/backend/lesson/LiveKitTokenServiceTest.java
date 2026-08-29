package com.okututor.backend.lesson;

import static org.assertj.core.api.Assertions.assertThat;

import com.okututor.backend.common.config.AppProperties;
import io.jsonwebtoken.Claims;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LiveKitTokenServiceTest {

    private LiveKitTokenService service;

    @BeforeEach
    @SuppressWarnings("unused")
    void setUp() {
        AppProperties props = new AppProperties();
        props.getLivekit().setApiKey("devkey");
        props.getLivekit().setApiSecret("s".repeat(48));
        props.getLivekit().setWsUrl("wss://livekit.example:7880");
        props.getLivekit().setTokenTtlMinutes(30);
        service = new LiveKitTokenService(props);
    }

    @Test
    @SuppressWarnings("unchecked")
    void issuedTokenCarriesRoomGrantIdentityAndTtl() {
        UUID bookingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        var meetingToken = service.issue(bookingId, userId, "Test User");

        assertThat(meetingToken.server_url()).isEqualTo("wss://livekit.example:7880");
        assertThat(meetingToken.room_name()).isEqualTo("booking-" + bookingId);

        Claims claims = service.parse(meetingToken.token());
        assertThat(claims.getIssuer()).isEqualTo("devkey");
        assertThat(claims.getSubject()).isEqualTo(userId.toString());

        Map<String, Object> video = (Map<String, Object>) claims.get("video");
        assertThat(video.get("room")).isEqualTo("booking-" + bookingId);
        assertThat(video.get("roomJoin")).isEqualTo(true);
        assertThat(video.get("canSubscribe")).isEqualTo(true);

        long ttlSeconds = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        assertThat(ttlSeconds).isBetween(29 * 60L * 1000, 31 * 60L * 1000);
    }

    @Test
    void roomNameIsDerivedFromBookingId() {
        UUID bookingId = UUID.randomUUID();
        assertThat(LiveKitTokenService.roomName(bookingId)).isEqualTo("booking-" + bookingId);
    }
}
