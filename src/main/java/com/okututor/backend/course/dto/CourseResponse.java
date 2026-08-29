package com.okututor.backend.course.dto;

import com.okututor.backend.course.Course;
import com.okututor.backend.user.User;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * публичная форма курса (mockData.js + поля CourseWizard).
 */
public record CourseResponse(
        UUID id,
        UUID teacher_id,
        String teacher_name,
        String title,
        String subject,
        String category,
        String description,
        java.math.BigDecimal price_per_hour,
        String currency,
        String location_type,
        String group_size,
        Object days,
        Object specific_days,
        Integer experience,
        Integer max_students,
        String status,
        String rejection_reason,
        String cover_url,
        java.math.BigDecimal average_rating,
        int reviews_count,
        Instant created_at
) {

    public static CourseResponse from(Course course) {
        User teacher = course.getTeacher();
        return new CourseResponse(
                course.getId(),
                teacher != null ? teacher.getId() : null,
                teacher != null ? teacher.getFullName() : null,
                course.getTitle(),
                course.getSubject(),
                course.getCategory(),
                course.getDescription(),
                course.getPricePerHour(),
                course.getCurrency(),
                course.getLocationType().name().toLowerCase(),
                course.getGroupSize().name().toLowerCase(),
                csvToList(course.getDays()),
                csvToList(course.getSpecificDays()),
                course.getExperience(),
                course.getMaxStudents(),
                course.getStatus().name(),
                course.getRejectionReason(),
                course.getCoverUrl(),
                course.getAverageRating(),
                course.getReviewsCount(),
                course.getCreatedAt());
    }

    private static java.util.List<String> csvToList(String csv) {
        return (csv == null || csv.isBlank())
                ? java.util.List.of()
                : java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
