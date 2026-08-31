package com.okututor.backend.messaging;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.media.MediaService;
import com.okututor.backend.media.MessageAttachmentRef;
import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/messages")
public class MessagingController {

    private final MessagingService messagingService;
    private final MediaService mediaService;
    private final UserService userService;

    public MessagingController(MessagingService messagingService, MediaService mediaService, UserService userService) {
        this.messagingService = messagingService;
        this.mediaService = mediaService;
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

    /**
     * двухшаговый загрузчик вложения: возвращает media_id, который затем
     * передаётся в send ({attachment_media_id}) — контракт фронта
     * (endpoints.messages.attachments, uploadAttachment).
     */
    @Operation(summary = "Upload message attachment",
            description = "multipart/form-data, поле ''file''. Изображения оптимизируются и получают"
                    + " миниатюру (thumbnail_url); файлы не более 10 MB. Возвращает ref с media_id для send.")
    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MessageAttachmentRef uploadAttachment(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "байты файла (jpg/png/webp/gif/pdf/txt/doc/docx)", required = true,
                    schema = @Schema(type = "string", format = "binary"))
            @RequestPart("file") MultipartFile file) {
        return MessageAttachmentRef.of(
                mediaService.storeMessageAttachment(currentUser(principal), file));
    }

    /** текстовое сообщение (JSON) или с приложенным ранее media_id. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public MessagingService.MessageResponse sendJson(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody(required = false) MessagingService.SendRequest request) {
        return messagingService.send(currentUser(principal), request);
    }

    /** text + file в одном multipart-запросе (form-поля: conversation_id, body; бинарное — file). */
    @Operation(summary = "Send message with file",
            description = "multipart/form-data: conversation_id (uuid), body (текст), file (байты;"
                    + " изображение оптимизируется + миниатюра, файлы до 10 MB).")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MessagingService.MessageResponse sendMultipart(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("conversation_id") String conversationId,
            @RequestParam(required = false) String body,
            @Parameter(description = "байты файла", schema = @Schema(type = "string", format = "binary"))
            @RequestPart(required = false) MultipartFile file) {
        UUID id;
        try {
            id = UUID.fromString(conversationId);
        } catch (IllegalArgumentException e) {
            throw new com.okututor.backend.common.error.FieldValidationException(
                    Map.of("conversation_id", "conversation_id is required"));
        }
        return messagingService.send(currentUser(principal),
                new MessagingService.SendRequest(id, body, null), file);
    }

    private User currentUser(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        return userService.requireById(principal.id());
    }
}
