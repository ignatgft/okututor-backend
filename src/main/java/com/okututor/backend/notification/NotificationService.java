package com.okututor.backend.notification;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    public record NotificationResponse(
            UUID id,
            String message,
            String type,
            boolean read,
            String link,
            Instant created_at,
            Map<String, Object> payload,
            String entity_type,
            String entity_id
    ) {}

    private final NotificationRepository repository;
    private final UserRepository userRepository;

    public NotificationService(NotificationRepository repository, UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    /**
     * Асинхронная отправка уведомления через фиксированный пул
     * (notificationExecutor), чтобы не блокировать вызывающий
     * транзакционный контекст и не плодить потоки.
     */
    @Async("notificationExecutor")
    @Transactional
    public void notify(UUID userId, String message, String type, String link) {
        notify(userId, message, type, link, null);
    }

    /** как {@link #notify(UUID,String,String,String)}, но с структурированным payload. */
    @Async("notificationExecutor")
    @Transactional
    public void notify(UUID userId, String message, String type, String link, Map<String, Object> payload) {
        notify(userId, message, type, link, payload, null, null);
    }

    /** с привязкой к сущности (entity_type + entity_id) — для таймлайна и дедупа напоминаний. */
    @Async("notificationExecutor")
    @Transactional
    public void notify(UUID userId, String message, String type, String link,
                       Map<String, Object> payload, String entityType, String entityId) {
        try {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || message == null || message.isBlank()) {
                return;
            }
            Notification notification = new Notification();
            notification.setUser(user);
            notification.setMessage(message);
            notification.setType(type == null ? "SYSTEM" : type);
            notification.setLink(link);
            notification.setPayload(payload == null ? null : new LinkedHashMap<>(payload));
            notification.setEntityType(entityType);
            notification.setEntityId(entityId);
            repository.save(notification);
        } catch (Exception ex) {
            // best-effort: сбой уведомления не должен ломать основной бизнес-запрос,
            // но и не должен быть "тихим" — логируем контекст (чувствительные данные не пишем)
            log.error("notification creation failed: type={} userId={} link={} entityId={}",
                    type, userId, link, entityId != null ? entityId : extractEntityId(payload), ex);
        }
    }

    /** true, если для сущности этого типа уже было такое же событие (дедуп напоминаний). */
    @Transactional(readOnly = true)
    public boolean existsForEntity(String entityType, String entityId, String type) {
        return !repository.findByEntityTypeAndEntityIdAndType(entityType, entityId, type).isEmpty();
    }

    /** извлекает id связанной сущности из payload, не логируя весь payload. */
    private static String extractEntityId(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        for (String key : java.util.List.of("enrollment_id", "booking_id", "course_id")) {
            Object value = payload.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }

    @Transactional(readOnly = true)
    public java.util.List<NotificationResponse> list(User user, int page, int size) {
        return repository.findByUserIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(Math.max(page, 0),
                        Math.min(Math.max(size, 1), 100)))
                .stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Long> unreadCount(User user) {
        return Map.of("count", repository.countByUserIdAndReadFalse(user.getId()));
    }

    @Transactional
    public void markRead(User user, UUID id) {
        Notification notification = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Notification not found"));
        if (!user.getId().equals(notification.getUser().getId())) {
            throw ApiException.forbidden("Not your notification");
        }
        notification.setRead(true);
        notification.setReadAt(Instant.now());
        repository.save(notification);
    }

    @Transactional
    public void markAllRead(User user) {
        repository.markAllReadForUser(user.getId(), Instant.now());
    }

    private NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(n.getId(), n.getMessage(), n.getType(), n.isRead(),
                n.getLink(), n.getCreatedAt(), n.getPayload(), n.getEntityType(), n.getEntityId());
    }
}
