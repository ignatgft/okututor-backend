package com.okututor.backend.messaging;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/messages")
public class MessagingController {

    private final MessagingService messagingService;
    private final UserService userService;

    public MessagingController(MessagingService messagingService, UserService userService) {
        this.messagingService = messagingService;
        this.userService = userService;
    }

    @GetMapping("/conversations")
    public List<MessagingService.ConversationResponse> conversations(
            @AuthenticationPrincipal UserPrincipal principal) {
        return messagingService.conversations(currentUser(principal));
    }

    /** открывает (или возвращает существующую) DIRECT-переписку с собеседником. */
    @PostMapping("/conversations")
    public MessagingService.ConversationResponse open(@AuthenticationPrincipal UserPrincipal principal,
                                                      @RequestBody(required = false) MessagingService.OpenConversationRequest body) {
        User user = currentUser(principal);
        UUID counterpartId = body == null ? null : body.user_id();
        if (counterpartId == null) {
            throw new com.okututor.backend.common.error.FieldValidationException(
                    Map.of("user_id", "user_id is required"));
        }
        String type = body.type() == null ? "DIRECT" : body.type().toUpperCase();
        if (!"DIRECT".equals(type)) {
            throw ApiException.validation("Only DIRECT conversations can be created from the client");
        }
        // авторизация: админ — с любым, остальные — только со связанными по заявке/админу
        messagingService.ensureCanOpen(user, counterpartId);
        return messagingService.openWith(user, counterpartId);
    }

    /** сообщения треда; фронт принимает и массив, и конверт {messages}. */
    @GetMapping("/conversations/{id}")
    public List<MessagingService.MessageResponse> thread(@AuthenticationPrincipal UserPrincipal principal,
                                                         @PathVariable UUID id) {
        return messagingService.messages(currentUser(principal), id);
    }

    @PostMapping
    public MessagingService.MessageResponse send(@AuthenticationPrincipal UserPrincipal principal,
                                                 @RequestBody(required = false) MessagingService.SendRequest request) {
        return messagingService.send(currentUser(principal), request);
    }

    private User currentUser(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        return userService.requireById(principal.id());
    }
}
