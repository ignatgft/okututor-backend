package com.okututor.backend.lesson;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/v1/livekit/webhook — приём событий LiveKit.
 *
 * Endpoint публичный на уровне Spring Security (у LiveKit нет нашего Bearer-токена),
 * но каждый запрос проверяется подписью: Authorization JWT (HS256, api-secret)
 * с sha256-claim хеша тела. Подпись не сошлась -> 401, тело даже не разбирается.
 *
 * Content-Type от LiveKit — application/webhook+json, поэтому тело читается
 * как raw string, а не через @RequestBody DTO (иначе Spring отвергнет media type).
 */
@RestController
public class LiveKitWebhookController {

    private final LiveKitWebhookService webhookService;

    public LiveKitWebhookController(LiveKitWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping(value = "/api/v1/livekit/webhook",
            consumes = {MediaType.APPLICATION_JSON_VALUE, "application/webhook+json", "*/*"})
    public ResponseEntity<String> receive(HttpServletRequest request) throws IOException {
        String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!webhookService.verifySignature(request.getHeader("Authorization"), body)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid signature");
        }
        webhookService.handle(body);
        // LiveKit ждёт 2xx — иначе повторит доставку
        return ResponseEntity.ok().build();
    }
}
