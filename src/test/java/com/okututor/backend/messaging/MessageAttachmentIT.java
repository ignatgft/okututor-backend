package com.okututor.backend.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.okututor.backend.media.AttachmentKind;
import com.okututor.backend.media.MessageAttachmentRef;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * IT: отправка сообщения с файлом-вложением через MessagingService.
 * Проверяет весь pipeline: upload -> optimize -> storage -> metadata ->
 * привязка к сообщению -> MessageResponse с attachment.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class MessageAttachmentIT {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine").withReuse(false);

    @Autowired UserRepository userRepository;
    @Autowired MessagingService messagingService;

    private User persisted(String email, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setRole(role);
        u.setVerified(true);
        return userRepository.save(u);
    }

    @Test
    @Transactional
    void sendMultipartMessageWithImageAttachment() {
        User admin = persisted("adm-img-%s@test.com".formatted(UUID.randomUUID()), Role.ADMIN);
        User stranger = persisted("str-img-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);

        MessagingService.ConversationResponse conv = messagingService.openWith(admin, stranger.getId());

        // minimal valid 1x1 PNG generated via ImageIO
        byte[] png = toPng();

        var file = new MockMultipartFile("file", "photo.png", "image/png", png);

        var request = new MessagingService.SendRequest(conv.id(), "See this photo", null);
        MessagingService.MessageResponse response = messagingService.send(admin, request, file);

        assertThat(response.id()).isNotNull();
        assertThat(response.body()).isEqualTo("See this photo");
        assertThat(response.attachment()).isNotNull();

        MessageAttachmentRef att = response.attachment();
        assertThat(att.url()).isNotBlank();
        assertThat(att.thumbnail_url()).isNotBlank();
        assertThat(att.filename()).isEqualTo("photo.png");
        assertThat(att.kind()).isEqualTo(AttachmentKind.IMAGE.name());
        assertThat(att.size()).isPositive();
    }

    @Test
    @Transactional
    void sendTwoStepMessageWithFileAttachment() {
        User admin = persisted("adm-file-%s@test.com".formatted(UUID.randomUUID()), Role.ADMIN);
        User stranger = persisted("str-file-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);

        MessagingService.ConversationResponse conv = messagingService.openWith(admin, stranger.getId());

        byte[] pdf = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n%%EOF".getBytes();
        var file = new MockMultipartFile("file", "doc.pdf", "application/pdf", pdf);

        // step 1: upload attachment -> get media_id
        var attachment = messagingService.send(admin,
                new MessagingService.SendRequest(conv.id(), "", null), file);
        assertThat(attachment.attachment()).isNotNull();
        assertThat(attachment.attachment().kind()).isEqualTo(AttachmentKind.FILE.name());
        assertThat(attachment.attachment().thumbnail_url()).isNull();
    }

    @Test
    @Transactional
    void sendTextOnlyMessageHasNoAttachment() {
        User admin = persisted("adm-txt-%s@test.com".formatted(UUID.randomUUID()), Role.ADMIN);
        User stranger = persisted("str-txt-%s@test.com".formatted(UUID.randomUUID()), Role.STUDENT);

        MessagingService.ConversationResponse conv = messagingService.openWith(admin, stranger.getId());

        var request = new MessagingService.SendRequest(conv.id(), "Hello there", null);
        MessagingService.MessageResponse response = messagingService.send(admin, request);

        assertThat(response.body()).isEqualTo("Hello there");
        assertThat(response.attachment()).isNull();
    }

    /** генерирует валидный 2x2 PNG через ImageIO. */
    private static byte[] toPng() {
        try {
            var img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            var baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
