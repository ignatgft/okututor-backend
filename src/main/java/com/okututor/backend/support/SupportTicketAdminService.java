package com.okututor.backend.support;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.notification.NotificationService;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserRepository;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** админская сторона саппорта: очередь тикетов, ответы, assign/status/priority. */
@Service
public class SupportTicketAdminService {

    private final SupportTicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final SupportTicketCore core;

    public SupportTicketAdminService(SupportTicketRepository ticketRepository,
                                     UserRepository userRepository,
                                     NotificationService notificationService,
                                     SupportTicketCore core) {
        this.ticketRepository = ticketRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.core = core;
    }

    @Transactional(readOnly = true)
    public Page<SupportService.TicketResponse> allForAdmin(int page, int size, String status, String priority, String q) {
        Specification<SupportTicket> spec = (root, query, cb) -> cb.conjunction();
        if (status != null && !status.isBlank()) {
            SupportTicket.Status parsed = SupportTicketCore.parseStatus(status);
            spec = spec.and((root, query2, cb) -> cb.equal(root.get("status"), parsed));
        }
        if (priority != null && !priority.isBlank()) {
            SupportTicket.Priority parsed = SupportTicketCore.parsePriority(priority);
            spec = spec.and((root, query2, cb) -> cb.equal(root.get("priority"), parsed));
        }
        if (q != null && !q.isBlank()) {
            spec = spec.and((root, query2, cb) ->
                    cb.like(cb.lower(root.get("subject")), "%" + q.trim().toLowerCase(Locale.ROOT) + "%"));
        }
        return ticketRepository.findAll(spec, pageable(page, size))
                .map(t -> SupportService.toResponse(t, true));
    }

    @Transactional
    public SupportService.TicketResponse getForAdmin(Long number) {
        SupportTicket ticket = core.byNumber(number);
        ticket.setAdminUnreadCount(0);
        return SupportService.toResponse(ticketRepository.save(ticket), true);
    }

    @Transactional
    public SupportService.TicketMessageResponse sendAdminMessage(User admin, Long number, String body) {
        SupportTicket ticket = core.byNumber(number);
        var message = core.postMessage(ticket, admin, body, false);
        if (ticket.getStatus() == SupportTicket.Status.OPEN
                || ticket.getStatus() == SupportTicket.Status.IN_PROGRESS) {
            ticket.setStatus(SupportTicket.Status.WAITING_FOR_USER);
        }
        if (ticket.getAssignedAdmin() == null) {
            ticket.setAssignedAdmin(admin);
        }
        ticket.setLastMessagePreview(SupportTicketCore.preview(body));
        ticketRepository.save(ticket);
        // см. комментарий в SupportTicketUserService.sendUserMessage:
        // entity-поля до flush, инкремент — после
        ticketRepository.flush();
        ticketRepository.incrementUserUnread(ticket.getId());
        notificationService.notify(ticket.getAuthorId(), "Support replied to ticket " + ticket.displayId(),
                "SUPPORT", "/support/tickets/" + ticket.displayId());
        return SupportTicketCore.toMessageResponse(message, ticket);
    }

    @Transactional
    public SupportService.TicketResponse reopenAsAdmin(Long number) {
        SupportTicket ticket = core.byNumber(number);
        ticket.setStatus(SupportTicket.Status.OPEN);
        return SupportService.toResponse(ticketRepository.save(ticket), true);
    }

    @Transactional
    public SupportService.TicketResponse assign(Long number, User actingAdmin, UUID targetAdminId) {
        SupportTicket ticket = core.byNumber(number);
        User admin = targetAdminId == null ? actingAdmin
                : userRepository.findById(targetAdminId)
                        .filter(u -> u.getRole() == Role.ADMIN || u.getRole() == Role.SUPER_ADMIN)
                        .orElseThrow(() -> ApiException.validation("Target is not an admin"));
        ticket.setAssignedAdmin(admin);
        if (ticket.getStatus() == SupportTicket.Status.OPEN) {
            ticket.setStatus(SupportTicket.Status.IN_PROGRESS);
        }
        return SupportService.toResponse(ticketRepository.save(ticket), true);
    }

    @Transactional
    public SupportService.TicketResponse setStatus(Long number, String status) {
        SupportTicket ticket = core.byNumber(number);
        ticket.setStatus(SupportTicketCore.parseStatus(status));
        return SupportService.toResponse(ticketRepository.save(ticket), true);
    }

    @Transactional
    public SupportService.TicketResponse setPriority(Long number, String priority) {
        SupportTicket ticket = core.byNumber(number);
        ticket.setPriority(SupportTicketCore.parsePriority(priority));
        return SupportService.toResponse(ticketRepository.save(ticket), true);
    }

    @Transactional(readOnly = true)
    public List<SupportService.TicketResponse.AgentRef> agents() {
        return userRepository.findByRoleIn(List.of(Role.ADMIN, Role.SUPER_ADMIN)).stream()
                .map(u -> new SupportService.TicketResponse.AgentRef(u.getId().toString(), u.getFullName(),
                        u.getEmail()))
                .toList();
    }

    private static Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }
}
