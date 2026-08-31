package com.okututor.backend.support;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.support.dto.SupportTicketCreateRequest;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import jakarta.validation.Valid;

@RestController
public class SupportController {

    private final SupportService supportService;
    private final UserService userService;

    public SupportController(SupportService supportService, UserService userService) {
        this.supportService = supportService;
        this.userService = userService;
    }

    // ---------- пользователь ----------

    @PostMapping("/api/v1/support/tickets")
    public SupportService.TicketResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                                @RequestBody(required = false) @Valid SupportTicketCreateRequest request) {
        return supportService.create(currentUser(principal), request);
    }

    @GetMapping("/api/v1/support/tickets")
    public Page<SupportService.TicketResponse> myTickets(@AuthenticationPrincipal UserPrincipal principal,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return supportService.mine(currentUser(principal), page, size);
    }

    @GetMapping("/api/v1/support/tickets/{id}")
    public SupportService.TicketResponse ticket(@AuthenticationPrincipal UserPrincipal principal,
                                                @PathVariable String id) {
        Long number = SupportService.parseDisplayId(id);
        if (isAdmin(principal)) {
            return supportService.getForAdmin(number);
        }
        return supportService.getForUser(currentUser(principal), number);
    }

    @GetMapping("/api/v1/support/tickets/{id}/messages")
    public Page<SupportService.TicketMessageResponse> messages(@AuthenticationPrincipal UserPrincipal principal,
                                                               @PathVariable String id,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "50") int size) {
        return supportService.messages(currentUser(principal), SupportService.parseDisplayId(id), page, size);
    }

    @PostMapping("/api/v1/support/tickets/{id}/messages")
    public SupportService.TicketMessageResponse sendMessage(@AuthenticationPrincipal UserPrincipal principal,
                                                            @PathVariable String id,
                                                            @RequestBody(required = false) Map<String, Object> body) {
        User sender = currentUser(principal);
        Long number = SupportService.parseDisplayId(id);
        String text = body == null ? null : (String) body.get("body");
        if (isAdmin(principal) && !supportService.byNumber(number).isAuthor(sender.getId())) {
            return supportService.sendAdminMessage(sender, number, text);
        }
        return supportService.sendUserMessage(sender, number, text);
    }

    /**
     * multipart-вариант отправки сообщения с файлом: form-поля body (опционально)
     * и file (байты; изображение оптимизируется + миниатюра, файлы до 10 MB).
     */
    @PostMapping(value = "/api/v1/support/tickets/{id}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SupportService.TicketMessageResponse sendMessageWithFile(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id,
            @RequestParam(required = false) String body,
            @RequestPart(required = false) MultipartFile file) {
        User sender = currentUser(principal);
        Long number = SupportService.parseDisplayId(id);
        if (isAdmin(principal) && !supportService.byNumber(number).isAuthor(sender.getId())) {
            return supportService.sendAdminMessage(sender, number, body, file);
        }
        return supportService.sendUserMessage(sender, number, body, file);
    }

    @PostMapping("/api/v1/support/tickets/{id}/read")
    public ResponseEntity<Void> markRead(@AuthenticationPrincipal UserPrincipal principal, @PathVariable String id) {
        supportService.markReadByUser(currentUser(principal), SupportService.parseDisplayId(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/support/tickets/{id}/close")
    public SupportService.TicketResponse close(@AuthenticationPrincipal UserPrincipal principal,
                                               @PathVariable String id) {
        return supportService.closeByUser(currentUser(principal), SupportService.parseDisplayId(id));
    }

    @PostMapping("/api/v1/support/tickets/{id}/reopen")
    public SupportService.TicketResponse reopen(@AuthenticationPrincipal UserPrincipal principal,
                                                @PathVariable String id) {
        User user = currentUser(principal);
        boolean adminSide = isAdmin(principal)
                && !supportService.byNumber(SupportService.parseDisplayId(id)).isAuthor(user.getId());
        return supportService.reopen(user, SupportService.parseDisplayId(id), adminSide);
    }

    // ---------- админ ----------

    @RestController
    @RequestMapping("/api/v1/admin/support")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    static class AdminSupportController {

        private final SupportService supportService;
        private final UserService userService;

        AdminSupportController(SupportService supportService, UserService userService) {
            this.supportService = supportService;
            this.userService = userService;
        }

        @GetMapping("/tickets")
        public Page<SupportService.TicketResponse> tickets(
                @RequestParam(required = false) String status,
                @RequestParam(required = false) String priority,
                @RequestParam(required = false) String q,
                @RequestParam(defaultValue = "0") int page,
                @RequestParam(defaultValue = "20") int size) {
            return supportService.allForAdmin(page, size, status, priority, q);
        }

        @GetMapping("/tickets/{id}")
        public SupportService.TicketResponse ticket(@PathVariable String id) {
            return supportService.getForAdmin(SupportService.parseDisplayId(id));
        }

        /** payload: { admin_id? } — нет/пусто значит назначить себя. */
        @PostMapping("/tickets/{id}/assign")
        public SupportService.TicketResponse assign(@AuthenticationPrincipal UserPrincipal principal,
                                                    @PathVariable String id,
                                                    @RequestBody(required = false) Map<String, Object> payload) {
            UUID targetId = parseUuid(payload == null ? null : payload.get("admin_id"));
            return supportService.assign(SupportService.parseDisplayId(id),
                    currentUser(principal), targetId);
        }

        @PostMapping("/tickets/{id}/take")
        public SupportService.TicketResponse take(@AuthenticationPrincipal UserPrincipal principal,
                                                  @PathVariable String id) {
            return supportService.assign(SupportService.parseDisplayId(id), currentUser(principal), null);
        }

        @PutMapping("/tickets/{id}/status")
        public SupportService.TicketResponse setStatus(@PathVariable String id,
                                                       @RequestBody Map<String, String> body) {
            return supportService.setStatus(SupportService.parseDisplayId(id),
                    body == null ? null : body.get("status"));
        }

        @PutMapping("/tickets/{id}/priority")
        public SupportService.TicketResponse setPriority(@PathVariable String id,
                                                         @RequestBody Map<String, String> body) {
            return supportService.setPriority(SupportService.parseDisplayId(id),
                    body == null ? null : body.get("priority"));
        }

        @GetMapping("/agents")
        public List<SupportService.TicketResponse.AgentRef> agents() {
            return supportService.agents();
        }

        private static UUID parseUuid(Object value) {
            try {
                return value == null || value.toString().isBlank()
                        ? null : UUID.fromString(value.toString());
            } catch (IllegalArgumentException e) {
                throw new com.okututor.backend.common.error.FieldValidationException(
                        Map.of("admin_id", "Invalid admin id"));
            }
        }

        private User currentUser(UserPrincipal principal) {
            if (principal == null) {
                throw ApiException.unauthorized("Authentication required");
            }
            return userService.requireById(principal.id());
        }
    }

    static void requireAuth(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("Authentication required");
        }
    }

    private boolean isAdmin(UserPrincipal principal) {
        requireAuth(principal);
        return principal.isAdminLike();
    }

    private User currentUser(UserPrincipal principal) {
        requireAuth(principal);
        return userService.requireById(principal.id());
    }
}
