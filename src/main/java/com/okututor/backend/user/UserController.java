package com.okututor.backend.user;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.media.MediaService;
import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.user.dto.PublicUserResponse;
import com.okututor.backend.user.dto.UpdateProfileRequest;
import com.okututor.backend.user.dto.UserResponse;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;
    private final MediaService mediaService;

    public UserController(UserService userService, UserMapper userMapper, MediaService mediaService) {
        this.userService = userService;
        this.userMapper = userMapper;
        this.mediaService = mediaService;
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UserPrincipal principal) {
        requireAuth(principal);
        return userMapper.toResponse(userService.requireById(principal.id()));
    }

    @PutMapping("/me")
    public UserResponse updateMe(@AuthenticationPrincipal UserPrincipal principal,
                                 @Valid @RequestBody UpdateProfileRequest request) {
        requireAuth(principal);
        return userService.updateProfile(userService.requireById(principal.id()), request);
    }

    /**
     * multipart/form-data; бинарное поле ожидается под именем ``file``.
     * Изображение оптимизируется (resize/crop/WebP) и уходит в object storage.
     */
    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> uploadAvatar(@AuthenticationPrincipal UserPrincipal principal,
                                            @RequestParam("file") MultipartFile file) {
        requireAuth(principal);
        return Map.of("avatar", mediaService.updateAvatar(userService.requireById(principal.id()), file));
    }

    /** legacy-путь фронта: PUT вместо POST. */
    @PutMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> replaceAvatar(@AuthenticationPrincipal UserPrincipal principal,
                                             @RequestParam("file") MultipartFile file) {
        requireAuth(principal);
        return Map.of("avatar", mediaService.updateAvatar(userService.requireById(principal.id()), file));
    }

    @DeleteMapping("/me/avatar")
    public ResponseEntity<Void> deleteAvatar(@AuthenticationPrincipal UserPrincipal principal) {
        requireAuth(principal);
        mediaService.deleteAvatar(userService.requireById(principal.id()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public PublicUserResponse byId(@PathVariable UUID id) {
        return userService.toPublic(userService.requireById(id));
    }

    /** публичный каталог репетиторов (фронт зовёт с auth=false). */
    @GetMapping("/tutors")
    public Page<UserResponse> tutors(@RequestParam(required = false) String q,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return userService.tutors(q, page, size);
    }

    private static void requireAuth(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("Authentication required");
        }
    }
}
