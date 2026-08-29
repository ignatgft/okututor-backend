package com.okututor.backend.support;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.FieldValidationException;
import com.okututor.backend.support.dto.SupportTicketCreateRequest;
import com.okututor.backend.user.User;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** пользовательская сторона саппорта: создание, свои тикеты, переписка автора. */
@Service
public class SupportTicketUserService {

    private static final Set<String> CATEGORIES = Set.of("TECHNICAL", "PAYMENT", "COURSE", "ACCOUNT", "BUG");

    private final SupportTicketRepository ticketRepository;
    private final SupportTicketMessageRepository messageRepository;
    private final SupportTicketCore core;

    public SupportTicketUserService(SupportTicketRepository ticketRepository,
                                    SupportTicketMessageRepository messageRepository,
                                    SupportTicketCore core) {
        this.ticketRepository = ticketRepository;
        this.messageRepository = messageRepository;
        this.core = core;
    }

    @Transactional
    public SupportService.TicketResponse create(User author, SupportTicketCreateRequest request) {
        String category = request == null || request.category() == null ? null
                : request.category().trim().toUpperCase(java.util.Locale.ROOT);
        String priority = request == null ? null : request.priority();
        String subject = request == null ? null : request.subject();
        String description = request == null ? null : request.description();

        if (category == null || !CATEGORIES.contains(category)) {
            throw new FieldValidationException(Map.of("category", "One of TECHNICAL/PAYMENT/COURSE/ACCOUNT/BUG"));
        }
        if (subject == null || subject.isBlank()) {
            throw new FieldValidationException(Map.of("subject", "Subject is required"));
        }
        if (description == null || description.isBlank()) {
            throw new FieldValidationException(Map.of("description", "Description is required"));
        }

        SupportTicket ticket = new SupportTicket();
        ticket.setNumber(ticketRepository.nextNumber());
        ticket.setAuthor(author);
        ticket.setCategory(SupportTicket.Category.valueOf(category));
        ticket.setPriority(priority == null ? SupportTicket.Priority.NORMAL : SupportTicketCore.parsePriority(priority));
        ticket.setSubject(subject.trim());
        ticket.setDescription(description.trim());
        ticket.setLastMessagePreview(SupportTicketCore.preview(description));
        ticket = ticketRepository.save(ticket);

        core.postMessage(ticket, author, description, false);
        return SupportService.toResponse(ticketRepository.save(ticket), false);
    }

    @Transactional(readOnly = true)
    public Page<SupportService.TicketResponse> mine(User user, int page, int size) {
        return ticketRepository.findByAuthorIdOrderByUpdatedAtDesc(user.getId(), pageable(page, size))
                .map(t -> SupportService.toResponse(t, false));
    }

    @Transactional
    public SupportService.TicketResponse getForUser(User user, Long number) {
        SupportTicket ticket = ownedTicket(number, user);
        ticket.setUserUnreadCount(0);
        return SupportService.toResponse(ticketRepository.save(ticket), false);
    }

    @Transactional
    public SupportService.TicketMessageResponse sendUserMessage(User user, Long number, String body) {
        SupportTicket ticket = ownedTicket(number, user);
        if (ticket.getStatus() == SupportTicket.Status.CLOSED) {
            throw ApiException.conflict("Ticket is closed. Reopen it first.");
        }
        var message = core.postMessage(ticket, user, body, false);
        if (ticket.getStatus() == SupportTicket.Status.OPEN || ticket.getStatus() == null) {
            ticket.setStatus(SupportTicket.Status.IN_PROGRESS);
        }
        ticket.setLastMessagePreview(SupportTicketCore.preview(body));
        ticketRepository.save(ticket);
        // порядок важен: сначала фиксируем поля сущности, затем атомарный
        // инкремент — иначе flush сущности затёр бы счётчик значением из памяти
        ticketRepository.flush();
        ticketRepository.incrementAdminUnread(ticket.getId());
        return SupportTicketCore.toMessageResponse(message, ticket);
    }

    @Transactional(readOnly = true)
    public Page<SupportService.TicketMessageResponse> messages(User viewer, Long number, int page, int size) {
        SupportTicket ticket = core.byNumber(number);
        if (!SupportTicketCore.isViewerAdmin(viewer) && !ticket.isAuthor(viewer.getId())) {
            throw ApiException.forbidden("Not your ticket");
        }
        return messageRepository.findByTicketNumberOrderByCreatedAtAsc(number, pageable(page, size))
                .map(m -> SupportTicketCore.toMessageResponse(m, ticket));
    }

    @Transactional
    public void markReadByUser(User user, Long number) {
        SupportTicket ticket = ownedTicket(number, user);
        ticket.setUserUnreadCount(0);
        ticketRepository.save(ticket);
    }

    @Transactional
    public SupportService.TicketResponse closeByUser(User user, Long number) {
        SupportTicket ticket = ownedTicket(number, user);
        if (ticket.getStatus() == SupportTicket.Status.CLOSED) {
            return SupportService.toResponse(ticket, false); // идемпотентно
        }
        ticket.setStatus(SupportTicket.Status.CLOSED);
        return SupportService.toResponse(ticketRepository.save(ticket), false);
    }

    @Transactional
    public SupportService.TicketResponse reopenAsUser(User user, Long number) {
        SupportTicket ticket = ownedTicket(number, user);
        ticket.setStatus(SupportTicket.Status.OPEN);
        return SupportService.toResponse(ticketRepository.save(ticket), false);
    }

    private SupportTicket ownedTicket(Long number, User user) {
        SupportTicket ticket = core.byNumber(number);
        if (!ticket.isAuthor(user.getId())) {
            throw ApiException.forbidden("Not your ticket");
        }
        return ticket;
    }

    private static Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }
}
