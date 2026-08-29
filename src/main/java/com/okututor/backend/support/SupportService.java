package com.okututor.backend.support;

import com.okututor.backend.support.dto.SupportTicketCreateRequest;
import com.okututor.backend.user.User;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * устаревший фасад: логика разделена между {@link SupportTicketUserService}
 * (сторона автора тикета) и {@link SupportTicketAdminService} (админская очередь).
 * Records ответов остаются здесь — на них завязан фронт и контроллеры.
 */
@Deprecated(forRemoval = true)
@Service
public class SupportService {

    public record TicketResponse(
            String id,
            String subject,
            String category,
            String status,
            String priority,
            Instant created_at,
            Instant updated_at,
            String last_message_preview,
            long unread_count,
            String assigned_admin_id,
            String assigned_admin_name,
            AuthorRef user
    ) {

        public record AgentRef(String id, String full_name, String email) {}
    }

    public record AuthorRef(String id, String name, String email) {}

    /** формат сообщения повторяет mockData.js: id msg-…, ticket_id TK-n, client_status SENT. */
    public record TicketMessageResponse(
            String id,
            String ticket_id,
            String sender_id,
            String sender_name,
            String sender_role,
            String body,
            Instant created_at,
            String type,
            List<Object> attachments,
            String client_status
    ) {}

    private final SupportTicketCore core;
    private final SupportTicketUserService userService;
    private final SupportTicketAdminService adminService;

    public SupportService(SupportTicketCore core,
                          SupportTicketUserService userService,
                          SupportTicketAdminService adminService) {
        this.core = core;
        this.userService = userService;
        this.adminService = adminService;
    }

    // ---------- делегирование (сохраняет контракт контроллеров) ----------

    /** @use {@link SupportTicketUserService#create} */
    @Transactional
    public TicketResponse create(User author, SupportTicketCreateRequest request) {
        return userService.create(author, request);
    }

    /** @use {@link SupportTicketUserService#mine} */
    @Transactional(readOnly = true)
    public Page<TicketResponse> mine(User user, int page, int size) {
        return userService.mine(user, page, size);
    }

    /** @use {@link SupportTicketAdminService#allForAdmin} */
    @Transactional(readOnly = true)
    public Page<TicketResponse> allForAdmin(int page, int size, String status, String priority, String q) {
        return adminService.allForAdmin(page, size, status, priority, q);
    }

    /** @use {@link SupportTicketUserService#getForUser} */
    @Transactional
    public TicketResponse getForUser(User user, Long number) {
        return userService.getForUser(user, number);
    }

    /** @use {@link SupportTicketAdminService#getForAdmin} */
    @Transactional
    public TicketResponse getForAdmin(Long number) {
        return adminService.getForAdmin(number);
    }

    /** @use {@link SupportTicketUserService#sendUserMessage} */
    @Transactional
    public TicketMessageResponse sendUserMessage(User user, Long number, String body) {
        return userService.sendUserMessage(user, number, body);
    }

    /** @use {@link SupportTicketAdminService#sendAdminMessage} */
    @Transactional
    public TicketMessageResponse sendAdminMessage(User admin, Long number, String body) {
        return adminService.sendAdminMessage(admin, number, body);
    }

    /** @use {@link SupportTicketUserService#messages} */
    @Transactional(readOnly = true)
    public Page<TicketMessageResponse> messages(User viewer, Long number, int page, int size) {
        return userService.messages(viewer, number, page, size);
    }

    /** @use {@link SupportTicketUserService#markReadByUser} */
    @Transactional
    public void markReadByUser(User user, Long number) {
        userService.markReadByUser(user, number);
    }

    /** @use {@link SupportTicketUserService#closeByUser} */
    @Transactional
    public TicketResponse closeByUser(User user, Long number) {
        return userService.closeByUser(user, number);
    }

    /** @use {@link SupportTicketUserService#reopenAsUser} / {@link SupportTicketAdminService#reopenAsAdmin} */
    @Transactional
    public TicketResponse reopen(User userOrAdmin, Long number, boolean adminSide) {
        return adminSide ? adminService.reopenAsAdmin(number) : userService.reopenAsUser(userOrAdmin, number);
    }

    /** @use {@link SupportTicketAdminService#assign} */
    @Transactional
    public TicketResponse assign(Long number, User actingAdmin, UUID targetAdminId) {
        return adminService.assign(number, actingAdmin, targetAdminId);
    }

    /** @use {@link SupportTicketAdminService#setStatus} */
    @Transactional
    public TicketResponse setStatus(Long number, String status) {
        return adminService.setStatus(number, status);
    }

    /** @use {@link SupportTicketAdminService#setPriority} */
    @Transactional
    public TicketResponse setPriority(Long number, String priority) {
        return adminService.setPriority(number, priority);
    }

    /** @use {@link SupportTicketAdminService#agents} */
    @Transactional(readOnly = true)
    public List<TicketResponse.AgentRef> agents() {
        return adminService.agents();
    }

    /** @use {@link SupportTicketCore#byNumber} */
    @Transactional(readOnly = true)
    public SupportTicket byNumber(Long number) {
        return core.byNumber(number);
    }

    // ---------- общие утилиты ----------

    public static Long parseDisplayId(String rawId) {
        return SupportTicketCore.parseDisplayId(rawId);
    }

    static TicketResponse toResponse(SupportTicket t, boolean adminView) {
        User admin = t.getAssignedAdmin();
        User author = t.getAuthor();
        return new TicketResponse(
                t.displayId(),
                t.getSubject(),
                t.getCategory().name(),
                t.getStatus().name(),
                t.getPriority().name(),
                t.getCreatedAt(),
                t.getUpdatedAt(),
                t.getLastMessagePreview(),
                adminView ? t.getAdminUnreadCount() : t.getUserUnreadCount(),
                admin != null ? admin.getId().toString() : null,
                admin != null ? admin.getFullName() : null,
                author != null ? new AuthorRef(author.getId().toString(), author.getFullName(),
                        author.getEmail()) : null);
    }
}
