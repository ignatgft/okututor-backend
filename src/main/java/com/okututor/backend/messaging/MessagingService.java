package com.okututor.backend.messaging;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.enrollment.Enrollment;
import com.okututor.backend.enrollment.EnrollmentRepository;
import com.okututor.backend.tutors.TutorApplication;
import com.okututor.backend.tutors.TutorApplicationRepository;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MessagingService {

    public record ConversationResponse(
            UUID id,
            String type,
            String counterpart_name,
            long unread_count,
            String last_message,
            Instant updated_at
    ) {}

    public record MessageResponse(
            UUID id,
            UUID conversation_id,
            UUID sender_id,
            String sender_name,
            String body,
            Instant created_at,
            Instant read_at
    ) {}

    public record SendRequest(UUID conversation_id, String body) {}

    public record OpenConversationRequest(UUID user_id, String type) {}

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UserService userService;
    private final EnrollmentRepository enrollmentRepository;
    private final TutorApplicationRepository tutorApplicationRepository;
    private final com.okututor.backend.notification.NotificationService notificationService;

    public MessagingService(ConversationRepository conversationRepository,
                            MessageRepository messageRepository,
                            UserService userService,
                            EnrollmentRepository enrollmentRepository,
                            TutorApplicationRepository tutorApplicationRepository,
                            com.okututor.backend.notification.NotificationService notificationService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.userService = userService;
        this.enrollmentRepository = enrollmentRepository;
        this.tutorApplicationRepository = tutorApplicationRepository;
        this.notificationService = notificationService;
    }

    @Transactional(readOnly = true)
    public List<ConversationResponse> conversations(User user) {
        return conversationRepository
                .findDirectForUser(user.getId(), PageRequest.of(0, 100))
                .stream()
                .map(c -> toResponse(c, user.getId()))
                .toList();
    }

    @Transactional
    public List<MessageResponse> messages(User user, UUID conversationId) {
        Conversation conversation = requireParticipant(conversationId, user.getId());
        // отмечаем сообщения собеседника прочитанными при открытии;
        // один запрос + явный saveAll (метод пишущий, не readOnly)
        List<Message> thread = messageRepository.findByConversation_IdOrderByCreatedAtAsc(conversation.getId());
        Instant now = Instant.now();
        List<Message> changed = new java.util.ArrayList<>();
        for (Message message : thread) {
            if (!user.getId().equals(message.getSenderId()) && message.getReadAt() == null) {
                message.setReadAt(now);
                changed.add(message);
            }
        }
        if (!changed.isEmpty()) {
            messageRepository.saveAll(changed);
        }
        return thread.stream()
                .map(m -> toMessageResponse(m, conversation))
                .toList();
    }

    @Transactional
    public MessageResponse send(User sender, SendRequest request) {
        if (request == null || request.conversation_id() == null) {
            throw new com.okututor.backend.common.error.FieldValidationException(
                    Map.of("conversation_id", "conversation_id is required"));
        }
        if (request.body() == null || request.body().isBlank()) {
            throw new com.okututor.backend.common.error.FieldValidationException(
                    Map.of("body", "Message must not be empty"));
        }
        Conversation conversation = requireParticipant(request.conversation_id(), sender.getId());

        Message message = new Message();
        message.setConversation(conversation);
        message.setSender(sender);
        message.setBody(request.body().trim());
        message = messageRepository.save(message);

        conversation.setLastMessage(message.getBody());
        conversation.setLastMessageAt(message.getCreatedAt());
        conversationRepository.save(conversation);

        UUID counterpartId = conversation.counterpartOf(sender.getId());
        if (counterpartId != null) {
            notificationService.notify(counterpartId, "New message from " + sender.getFullName(),
                    "MESSAGE", "/messages");
        }

        return toMessageResponse(message, conversation);
    }

    /** открывает (при отсутствии создаёт) DIRECT-переписку с другим пользователем. */
    @Transactional
    public ConversationResponse openWith(User user, UUID counterpartId) {
        User counterpart = userService.requireById(counterpartId);
        if (counterpart.getId().equals(user.getId())) {
            throw ApiException.validation("You cannot message yourself");
        }
        UUID lowId = user.getId().compareTo(counterpart.getId()) < 0 ? user.getId() : counterpart.getId();
        UUID highId = user.getId().compareTo(counterpart.getId()) < 0 ? counterpart.getId() : user.getId();

        Conversation conversation = conversationRepository
                .findByTypeAndUser1_IdAndUser2_Id(Conversation.Type.DIRECT, lowId, highId)
                .orElseGet(() -> {
                    Conversation fresh = new Conversation();
                    fresh.setType(Conversation.Type.DIRECT);
                    fresh.setUser1(userService.requireById(lowId));
                    fresh.setUser2(userService.requireById(highId));
                    return fresh;
                });
        return toResponse(conversationRepository.save(conversation), user.getId());
    }

    @Transactional(readOnly = true)
    public long unreadFor(User user) {
        return messageRepository.countUnreadAcrossConversations(user.getId());
    }

    /**
     * Проверка права открыть DIRECT-переписку с собеседником.
     * ADMIN/SUPER_ADMIN — с любым; студент/тьютор — только при связи
     * (ACCEPTED-заявка между ними) или если у него есть своя заявка «стать тьютором»
     * и собеседник — админ.
     */
    @Transactional(readOnly = true)
    public void ensureCanOpen(User user, UUID counterpartId) {
        if (user == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        boolean adminLike = user.getRole() == Role.ADMIN || user.getRole() == Role.SUPER_ADMIN;
        if (adminLike) {
            return;
        }
        if (counterpartId.equals(user.getId())) {
            throw ApiException.validation("You cannot message yourself");
        }
        boolean linked = hasAcceptedEnrollment(user.getId(), counterpartId);
        if (linked) {
            return;
        }
        // своя заявка «стать тьютором» + собеседник-админ
        boolean applicant = tutorApplicationRepository.findByUserId(user.getId())
                .map(a -> a.getStatus() == TutorApplication.Status.PENDING
                        || a.getStatus() == TutorApplication.Status.REJECTED)
                .orElse(false);
        if (applicant) {
            User counterpart = userService.requireById(counterpartId);
            if (counterpart.getRole() == Role.ADMIN || counterpart.getRole() == Role.SUPER_ADMIN) {
                return;
            }
        }
        throw ApiException.forbidden("You can open a chat only with your tutor after the request is accepted");
    }

    private boolean hasAcceptedEnrollment(UUID me, UUID other) {
        return enrollmentRepository.existsAcceptedBetween(me, other);
    }

    private Conversation requireParticipant(UUID conversationId, UUID userId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> ApiException.notFound("Conversation not found"));
        if (!conversation.involves(userId)) {
            throw ApiException.forbidden("Not your conversation");
        }
        return conversation;
    }

    private ConversationResponse toResponse(Conversation c, UUID viewerId) {
        User counterpart = viewerId.equals(c.getUser1Id()) ? c.getUser2() : c.getUser1();
        return new ConversationResponse(
                c.getId(),
                c.getType().name(),
                counterpart != null ? counterpart.getFullName() : null,
                messageRepository.countUnread(c.getId(), viewerId),
                c.getLastMessage(),
                c.getLastMessageAt() != null ? c.getLastMessageAt() : c.getCreatedAt());
    }

    private MessageResponse toMessageResponse(Message m, Conversation ignored) {
        User sender = m.getSender();
        return new MessageResponse(
                m.getId(),
                m.getConversation() != null ? m.getConversation().getId() : null,
                m.getSenderId(),
                sender != null ? sender.getFullName() : null,
                m.getBody(),
                m.getCreatedAt(),
                m.getReadAt());
    }
}
