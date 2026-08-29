package com.okututor.backend.media;

import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.user.UserService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * обложка курса (#36): POST /api/v1/courses/{courseId}/cover.
 * Аватарные эндпоинты живут в UserController (PUT-совместимость с фронтом).
 */
@RestController
@RequestMapping("/api/v1")
public class MediaController {

    private final MediaService mediaService;
    private final UserService userService;

    public MediaController(MediaService mediaService, UserService userService) {
        this.mediaService = mediaService;
        this.userService = userService;
    }

    /** multipart/form-data; бинарное поле — ``file``; только владелец-репетитор или админ. */
    @PostMapping(value = "/courses/{courseId}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> uploadCourseCover(@AuthenticationPrincipal UserPrincipal principal,
                                                 @PathVariable UUID courseId,
                                                 @RequestParam("file") MultipartFile file) {
        requireAuth(principal);
        return Map.of("cover_url",
                mediaService.updateCourseCover(userService.requireById(principal.id()), courseId, file));
    }

    private static void requireAuth(UserPrincipal principal) {
        if (principal == null) {
            throw com.okututor.backend.common.error.ApiException.unauthorized("Authentication required");
        }
    }
}
