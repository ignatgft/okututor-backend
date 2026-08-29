package com.okututor.backend.course;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.FieldValidationException;
import com.okututor.backend.course.dto.CourseCreateRequest;
import com.okututor.backend.course.dto.CourseResponse;
import com.okututor.backend.course.dto.CourseUpdateRequest;
import com.okututor.backend.user.User;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseService {

    private final CourseRepository repository;

    public CourseService(CourseRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Page<CourseResponse> search(String q, String subject, String locationType, String groupSize,
                                       BigDecimal maxPrice, BigDecimal priceMin, Double ratingMin,
                                       int page, int size, Course.Status statusFilter) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return repository.search(
                blankToNull(q) == null ? null : q.trim().toLowerCase(),
                blankToNull(subject),
                parseEnum(locationType, Course.LocationType.class, "location_type"),
                parseEnum(groupSize, Course.GroupSize.class, "group_size"),
                maxPrice,
                priceMin,
                ratingMin == null || ratingMin < 1 || ratingMin > 5 ? null : ratingMin,
                statusFilter,
                pageable).map(CourseResponse::from);
    }

    /** публичный каталог: только одобренные курсы. */
    @Transactional(readOnly = true)
    public Page<CourseResponse> listApproved(int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return repository.findByStatusOrderByAverageRatingDescCreatedAtDesc(Course.Status.APPROVED, pageable)
                .map(CourseResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<CourseResponse> popular(int limit) {
        return listApproved(0, limit);
    }

    @Transactional(readOnly = true)
    public CourseResponse getById(UUID id) {
        return CourseResponse.from(repository.findByIdWithTeacher(id)
                .orElseThrow(() -> ApiException.notFound("Course not found")));
    }

    /**
     * публичный просмотр с учётом видимости: аноним видит только APPROVED,
     * владелец/админ — любые свои статусы. Загрузка join fetch'ем + маппинг
     * внутри транзакции: ни LazyInitializationException, ни лишнего запроса.
     */
    @Transactional(readOnly = true)
    public CourseResponse view(UUID id, User viewerOrNull) {
        Course course = repository.findByIdWithTeacher(id)
                .orElseThrow(() -> ApiException.notFound("Course not found"));
        if (course.getStatus() != Course.Status.APPROVED) {
            if (viewerOrNull == null) {
                throw ApiException.notFound("Course not found");
            }
            try {
                requireOwnerOrAdmin(viewerOrNull, course);
            } catch (ApiException forbidden) {
                throw ApiException.notFound("Course not found");
            }
        }
        return CourseResponse.from(course);
    }

    @Transactional(readOnly = true)
    public Course requireById(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Course not found"));
    }

    @Transactional
    public CourseResponse create(User teacher, CourseCreateRequest request) {
        if (request == null) {
            throw new FieldValidationException(Map.of("title", "Title is required"));
        }
        Course course = new Course();
        course.setTeacher(teacher);
        applyCreate(course, request);
        return CourseResponse.from(repository.save(course));
    }

    @Transactional
    public CourseResponse update(User actor, UUID id, CourseUpdateRequest payload) {
        Course course = requireById(id);
        requireOwnerOrAdmin(actor, course);
        applyUpdate(course, payload == null ? new CourseUpdateRequest(null, null, null, null, null,
                null, null, null, null, null, null, null, null) : payload);
        return CourseResponse.from(repository.save(course));
    }

    @Transactional
    public void delete(User actor, UUID id) {
        Course course = requireById(id);
        requireOwnerOrAdmin(actor, course);
        repository.delete(course);
    }

    @Transactional(readOnly = true)
    public Page<CourseResponse> byTeacher(UUID teacherId, User viewer, int page, int size) {
        boolean selfOrAdmin = viewer != null && (viewer.getId().equals(teacherId) || isAdmin(viewer));
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        // фильтр статуса в SQL: totalElements/totalPages считаются корректно,
        // в отличие от прежней фильтрации Page в памяти
        Page<Course> result = selfOrAdmin
                ? repository.findByTeacherIdOrderByCreatedAtDesc(teacherId, pageable)
                : repository.findByTeacherIdAndStatusOrderByCreatedAtDesc(teacherId, Course.Status.APPROVED,
                        pageable);
        return result.map(CourseResponse::from);
    }

    @Transactional
    public Course save(Course course) {
        return repository.save(course);
    }

    // ---------- админская модерация ----------

    @Transactional(readOnly = true)
    public long countAll() {
        return repository.count();
    }

    @Transactional(readOnly = true)
    public Page<CourseResponse> allCourses(int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return repository.findAllByOrderByCreatedAtDesc(pageable).map(CourseResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<CourseResponse> byStatus(Course.Status status, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return repository.findByStatusOrderByCreatedAtDesc(status, pageable).map(CourseResponse::from);
    }

    @Transactional
    public CourseResponse moderate(UUID courseId, Course.Status targetStatus, String reason) {
        Course course = requireById(courseId);
        course.setStatus(targetStatus);
        course.setRejectionReason(targetStatus == Course.Status.REJECTED ? reason : null);
        return CourseResponse.from(repository.save(course));
    }

    public static void requireOwnerOrAdmin(User actor, Course course) {        UUID teacherId = course.getTeacher() != null ? course.getTeacher().getId() : null;
        boolean owner = teacherId != null && teacherId.equals(actor.getId());
        if (!owner && !isAdmin(actor)) {
            throw ApiException.forbidden("You do not own this course");
        }
    }

    private static boolean isAdmin(User user) {
        return user.getRole() == com.okututor.backend.user.Role.ADMIN
                || user.getRole() == com.okututor.backend.user.Role.SUPER_ADMIN;
    }

    /** создание: обязательные поля проверены bean-валидацией, остальное — дефолты. */
    private void applyCreate(Course course, CourseCreateRequest r) {
        if (r.title() == null || r.title().isBlank()) {
            throw new FieldValidationException(Map.of("title", "Title is required"));
        }
        if (r.subject() == null || r.subject().isBlank()) {
            throw new FieldValidationException(Map.of("subject", "Subject is required"));
        }
        course.setTitle(r.title().trim());
        course.setDescription(r.description());
        course.setSubject(r.subject().trim());
        course.setCategory(r.category());
        course.setPricePerHour(r.price_per_hour() == null ? BigDecimal.ZERO : r.price_per_hour());
        if (r.currency() != null && !r.currency().isBlank()) {
            course.setCurrency(r.currency());
        }
        course.setLocationType(parseLocationType(r.location_type()));
        course.setGroupSize(parseGroupSize(r.group_size()));
        course.setDays(joinCsv(r.days()));
        course.setSpecificDays(joinCsv(r.specific_days()));
        course.setExperience(r.experience());
        course.setMaxStudents(r.max_students() == null ? Integer.valueOf(1) : r.max_students());
        String statusRaw = strOrNull(r.status());
        course.setStatus(statusRaw == null ? Course.Status.DRAFT : parseStatus(statusRaw));
    }

    /** обновление: частичный PATCH — null в записи означает «не менять». */
    private void applyUpdate(Course course, CourseUpdateRequest r) {
        if (r.title() != null) {
            course.setTitle(r.title());
        }
        if (r.description() != null) {
            course.setDescription(r.description());
        }
        if (r.subject() != null) {
            course.setSubject(r.subject());
        }
        if (r.category() != null) {
            course.setCategory(r.category());
        }
        if (r.price_per_hour() != null) {
            course.setPricePerHour(r.price_per_hour());
        }
        if (r.currency() != null) {
            course.setCurrency(r.currency());
        }
        if (r.location_type() != null) {
            course.setLocationType(parseLocationType(r.location_type()));
        }
        if (r.group_size() != null) {
            course.setGroupSize(parseGroupSize(r.group_size()));
        }
        if (r.days() != null) {
            course.setDays(joinCsv(r.days()));
        }
        if (r.specific_days() != null) {
            course.setSpecificDays(joinCsv(r.specific_days()));
        }
        if (r.experience() != null) {
            course.setExperience(r.experience());
        }
        if (r.max_students() != null) {
            course.setMaxStudents(r.max_students());
        }
        if (strOrNull(r.status()) != null) {
            course.setStatus(parseStatus(r.status()));
            // публикация черновика снова проходит модерацию
            if (course.getStatus() == Course.Status.APPROVED && "PENDING".equalsIgnoreCase(r.status())) {
                course.setStatus(Course.Status.PENDING);
            }
        }
    }

    public static Course.Status parseStatus(String raw) {
        try {
            return Course.Status.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("Unknown status: " + raw);
        }
    }

    private static Course.LocationType parseLocationType(String raw) {
        if (raw == null) {
            throw new FieldValidationException(Map.of("location_type", "location_type is required"));
        }
        try {
            return Course.LocationType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new FieldValidationException(Map.of("location_type", "Expected online|offline"));
        }
    }

    private static Course.GroupSize parseGroupSize(String raw) {
        if (raw == null) {
            throw new FieldValidationException(Map.of("group_size", "group_size is required"));
        }
        try {
            return Course.GroupSize.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new FieldValidationException(Map.of("group_size", "Expected individual|group"));
        }
    }

    /** список строк из DTO → CSV-строка для колонки (null, если пусто). */
    private static String joinCsv(java.util.List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String joined = String.join(",", values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .toList());
        return joined.isEmpty() ? null : joined;
    }

    private static String strOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static <E extends Enum<E>> E parseEnum(String raw, Class<E> type, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new FieldValidationException(Map.of(field, "Unknown value: " + raw));
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
