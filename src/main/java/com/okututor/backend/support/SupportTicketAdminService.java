package com.okututor.backend.support;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.media.MediaService;
import com.okututor.backend.media.MessageAttachment;
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
import org.springframework.web.multipart.MultipartFile;

/** админская сторона саппорта: очередь тикетов, ответы, assign/status/priority. */
@Service
public class SupportTicketAdminService {

    private final SupportTicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final SupportTicketCore core;
    private final MediaService mediaService;

    public SupportTicketAdminService(SupportTicketRepository ticketRepository,
                                     UserRepository userRepository,
                                     NotificationService notificationService,
                                     SupportTicketCore core,
                                     MediaService mediaService) {
        this.ticketRepository = ticketRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.core = core;
        this.mediaService = mediaService;
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
        return sendAdminMessage(admin, number, body, null);
    }

    /** ответ админа с вложением (multipart: body + file). */
    @Transactional
    public SupportService.TicketMessageResponse sendAdminMessage(User admin, Long number,
                                                                 String body, MultipartFile file) {
        SupportTicket ticket = core.byNumber(number);
        String text = body == null ? "" : body.trim();
        MessageAttachment attachment = (file != null && !file.isEmpty())
                ? mediaService.storeClaimedMessageAttachment(admin, file)
                : null;
        var message = core.postMessage(ticket, admin, text, false, attachment);
        if (ticket.getStatus() == SupportTicket.Status.OPEN
                || ticket.getStatus() == SupportTicket.Status.IN_PROGRESS) {
            ticket.setStatus(SupportTicket.Status.WAITING_FOR_USER);
        }
        if (ticket.getAssignedAdmin() == null) {
            ticket.setAssignedAdmin(admin);
        }
        String preview = text.isEmpty() && attachment != null
                ? attachment.getOriginalFilename() : SupportTicketCore.preview(text);
        ticket.setLastMessagePreview(preview);
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
