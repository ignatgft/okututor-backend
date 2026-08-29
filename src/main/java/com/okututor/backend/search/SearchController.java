package com.okututor.backend.search;

import com.okututor.backend.common.api.PageResponse;
import com.okututor.backend.course.dto.CourseResponse;
import com.okututor.backend.security.UserPrincipal;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final CourseSearchService courseSearchService;

    public SearchController(CourseSearchService courseSearchService) {
        this.courseSearchService = courseSearchService;
    }

    @GetMapping("/courses")
    public PageResponse<CourseResponse> searchCourses(
            @RequestParam(required = false)
            @Size(max = 200, message = "q must be at most 200 characters") String q,
            @RequestParam(required = false) String subject,
            @RequestParam(name = "location_type", required = false) String locationType,
            @RequestParam(name = "group_size", required = false) String groupSize,
            @RequestParam(name = "max_price", required = false)
            @DecimalMin(value = "0", message = "max_price must be >= 0") BigDecimal maxPrice,
            @RequestParam(name = "price_min", required = false)
            @DecimalMin(value = "0", message = "price_min must be >= 0") BigDecimal priceMin,
            @RequestParam(name = "rating_min", required = false)
            @DecimalMin(value = "1", message = "rating_min must be in 1..5")
            @DecimalMax(value = "5", message = "rating_min must be in 1..5") Double ratingMin,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "page must be >= 0") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size must be >= 1")
            @Max(value = 100, message = "size must be <= 100") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        Page<CourseResponse> results = courseSearchService.search(
                q, subject, locationType, groupSize, maxPrice, priceMin, ratingMin, page, size,
                principal == null ? null : principal.id());

        return PageResponse.of(results);
    }

    @GetMapping("/tutors")
    public PageResponse<Map<String, Object>> searchTutors(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return PageResponse.empty();
    }

    /**
     * Поиск v2: results + extracted_filters + explanation (спека #17).
     * v1 {@code /search/courses} сохранён без изменений.
     */
    @GetMapping("/courses/v2")
    public SearchV2Response searchCoursesV2(
            @RequestParam(required = false)
            @Size(max = 200, message = "q must be at most 200 characters") String q,
            @RequestParam(required = false) String subject,
            @RequestParam(name = "location_type", required = false) String locationType,
            @RequestParam(name = "group_size", required = false) String groupSize,
            @RequestParam(name = "max_price", required = false)
            @DecimalMin(value = "0", message = "max_price must be >= 0") BigDecimal maxPrice,
            @RequestParam(name = "price_min", required = false)
            @DecimalMin(value = "0", message = "price_min must be >= 0") BigDecimal priceMin,
            @RequestParam(name = "rating_min", required = false)
            @DecimalMin(value = "1", message = "rating_min must be in 1..5")
            @DecimalMax(value = "5", message = "rating_min must be in 1..5") Double ratingMin,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "page must be >= 0") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size must be >= 1")
            @Max(value = 100, message = "size must be <= 100") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        return courseSearchService.searchV2(
                q, subject, locationType, groupSize, maxPrice, priceMin, ratingMin, page, size,
                principal == null ? null : principal.id());
    }

    @GetMapping("/suggestions")
    public Map<String, Object> suggestions(
            @RequestParam
            @Size(max = 200, message = "q must be at most 200 characters") String q) {
        List<Map<String, Object>> courses = courseSearchService.suggestions(q);
        List<Map<String, Object>> tutors = courseSearchService.tutorSuggestions(q);
        return Map.of("courses", courses, "tutors", tutors);
    }
}
