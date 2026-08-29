package com.okututor.backend.course;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.course.dto.CourseCreateRequest;
import com.okututor.backend.course.dto.CourseResponse;
import com.okututor.backend.course.dto.CourseUpdateRequest;
import com.okututor.backend.security.UserPrincipal;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserService;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {

    private final CourseService courseService;
    private final UserService userService;

    public CourseController(CourseService courseService, UserService userService) {
        this.courseService = courseService;
        this.userService = userService;
    }

    /** публичный поиск по APPROVED-курсам. */
    @GetMapping
    public Page<CourseResponse> list(@RequestParam(required = false) String q,
                                     @RequestParam(required = false) String subject,
                                     @RequestParam(name = "location_type", required = false) String locationType,
                                     @RequestParam(name = "group_size", required = false) String groupSize,
                                     @RequestParam(name = "max_price", required = false) BigDecimal maxPrice,
                                     @RequestParam(name = "price_min", required = false) BigDecimal priceMin,
                                     @RequestParam(name = "rating_min", required = false) Double ratingMin,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return courseService.search(q, subject, locationType, groupSize, maxPrice, priceMin, ratingMin,
                page, size, Course.Status.APPROVED);
    }

    @GetMapping("/popular")
    public Page<CourseResponse> popular(@RequestParam(defaultValue = "8") int limit) {
        return courseService.popular(limit);
    }

    @PostMapping
    @PreAuthorize("hasRole('TUTOR')")
    public CourseResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                 @Valid @RequestBody CourseCreateRequest request) {
        User teacher = currentUser(principal);
        return courseService.create(teacher, request);
    }

    /**
     * публичный просмотр: анонимы видят только APPROVED-курсы; владельцы/админы — и свои черновики.
     * Маппинг в DTO выполняется внутри сервисной транзакции (lazy teacher при open-in-view=false).
     */
    @GetMapping("/{id}")
    public CourseResponse byId(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal principal) {
        User viewer = principal == null ? null : userService.requireById(principal.id());
        return courseService.view(id, viewer);
    }

    @PutMapping("/{id}")
    public CourseResponse update(@AuthenticationPrincipal UserPrincipal principal,
                                 @PathVariable UUID id,
                                 @RequestBody(required = false) CourseUpdateRequest payload) {
        return courseService.update(currentUser(principal), id, payload);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID id) {
        courseService.delete(currentUser(principal), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/teacher/{teacherId}")
    public Page<CourseResponse> byTeacher(@AuthenticationPrincipal UserPrincipal principal,
                                          @PathVariable UUID teacherId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        User viewer = principal == null ? null : userService.requireById(principal.id());
        return courseService.byTeacher(teacherId, viewer, page, size);
    }

    private User currentUser(UserPrincipal principal) {
        if (principal == null) {
            throw ApiException.unauthorized("Authentication required");
        }
        return userService.requireById(principal.id());
    }
}
