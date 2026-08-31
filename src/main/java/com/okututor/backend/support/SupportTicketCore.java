package com.okututor.backend.support;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.FieldValidationException;
import com.okututor.backend.media.MessageAttachment;
import com.okututor.backend.media.MessageAttachmentRef;
import com.okututor.backend.user.User;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** общие операции работы с тикетами для user- и admin-сервисов. */
@Component
public class SupportTicketCore {

    private final SupportTicketRepository ticketRepository;
    private final SupportTicketMessageRepository messageRepository;

    SupportTicketCore(SupportTicketRepository ticketRepository,
                      SupportTicketMessageRepository messageRepository) {
        this.ticketRepository = ticketRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional(readOnly = true)
    public SupportTicket byNumber(Long number) {
        return ticketRepository.findByNumber(number)
                .orElseThrow(() -> ApiException.notFound("Ticket not found"));
    }

    public SupportTicketMessage postMessage(SupportTicket ticket, User sender, String body, boolean internalNote) {
        return postMessage(ticket, sender, body, internalNote, null);
    }

    public SupportTicketMessage postMessage(SupportTicket ticket, User sender, String body,
                                            boolean internalNote, MessageAttachment attachment) {
        String text = body == null ? "" : body.trim();
        if (text.isEmpty() && attachment == null) {
            throw new FieldValidationException(Map.of("body", "Message must not be empty"));
        }
        SupportTicketMessage message = new SupportTicketMessage();
        message.setTicket(ticket);
        message.setSender(sender);
        message.setSenderRole(sender.getRole().name());
        message.setBody(text);
        message.setAttachment(attachment);
        message.setType(internalNote ? SupportTicketMessage.Type.INTERNAL_NOTE
                : SupportTicketMessage.Type.USER_VISIBLE);
        return messageRepository.save(message);
    }

    public static Long parseDisplayId(String rawId) {
        try {
            String normalized = rawId.startsWith("TK-") ? rawId.substring(3) : rawId;
            return Long.parseLong(normalized);
        } catch (RuntimeException e) {
            throw ApiException.notFound("Ticket not found");
        }
    }

    public static boolean isViewerAdmin(User viewer) {
        return viewer.getRole() == com.okututor.backend.user.Role.ADMIN
                || viewer.getRole() == com.okututor.backend.user.Role.SUPER_ADMIN;
    }

    public static SupportTicket.Priority parsePriority(String raw) {
        try {
            return SupportTicket.Priority.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new FieldValidationException(Map.of("priority", "One of LOW/NORMAL/HIGH/URGENT"));
        }
    }

    public static SupportTicket.Status parseStatus(String raw) {
        try {
            return SupportTicket.Status.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("Unknown ticket status: " + raw);
        }
    }

    public static String preview(String text) {
        String trimmed = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return trimmed.length() <= 120 ? trimmed : trimmed.substring(0, 117) + "...";
    }

    /** формат сообщения повторяет mockData.js: id msg-…, ticket_id TK-n, client_status SENT. */
    public static SupportService.TicketMessageResponse toMessageResponse(SupportTicketMessage m,
                                                                         SupportTicket ticket) {
        User sender = m.getSender();
        MessageAttachment attachment = m.getAttachment();
        return new SupportService.TicketMessageResponse(
                "msg-" + m.getId().toString().substring(0, 8),
                ticket.displayId(),
                m.getSenderId() != null ? m.getSenderId().toString() : null,
                sender != null ? sender.getFullName() : null,
                m.getSenderRole(),
                m.getBody(),
                m.getCreatedAt(),
                m.getType().name(),
                attachment != null ? List.of(MessageAttachmentRef.of(attachment)) : List.of(),
                "SENT");
    }
}
