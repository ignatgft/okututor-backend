package com.okututor.backend.lesson;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.course.Course;
import com.okututor.backend.course.CourseRepository;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LessonService {

    public record LessonResponse(
            UUID id,
            String title,
            String counterpart,
            Instant start_at,
            String status,
            boolean joinable,
            UUID booking_id
    ) {}

    private final LessonRepository lessonRepository;
    private final CourseRepository courseRepository;
    private final UserService userService;

    public LessonService(LessonRepository lessonRepository, CourseRepository courseRepository, UserService userService) {
        this.lessonRepository = lessonRepository;
        this.courseRepository = courseRepository;
        this.userService = userService;
    }

    @Transactional(readOnly = true)
    public Page<LessonResponse> forUser(User user, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<Lesson> lessons = user.getRole() == com.okututor.backend.user.Role.STUDENT
                ? lessonRepository.findByStudentId(user.getId(), pageable)
                : lessonRepository.findByTeacherId(user.getId(), pageable);
        return lessons.map(l -> toResponse(l, user.getId()));
    }

    @Transactional(readOnly = true)
    public Lesson requireById(UUID id) {
        return lessonRepository.findById(id).orElseThrow(() -> ApiException.notFound("Lesson not found"));
    }

    @Transactional
    public Lesson create(User tutor, UUID courseId, UUID studentId, String title, Instant startAt) {
        User student = userService.requireById(studentId);
        Lesson lesson = new Lesson();
        lesson.setTeacher(tutor);
        lesson.setStudent(student);
        lesson.setTitle(title == null || title.isBlank() ? "Tutoring session" : title.trim());
        lesson.setStartAt(startAt);
        if (courseId != null) {
            Course course = courseRepository.findById(courseId)
                    .orElseThrow(() -> ApiException.notFound("Course not found"));
            lesson.setCourse(course);
        }
        return lessonRepository.save(lesson);
    }

    @Transactional
    public Lesson start(UUID id) {
        Lesson lesson = requireById(id);
        transition(lesson, Lesson.Status.IN_PROGRESS);
        return lessonRepository.save(lesson);
    }

    @Transactional
    public Lesson complete(UUID id) {
        Lesson lesson = requireById(id);
        transition(lesson, Lesson.Status.COMPLETED);
        return lessonRepository.save(lesson);
    }

    @Transactional
    public Lesson cancel(UUID id) {
        Lesson lesson = requireById(id);
        transition(lesson, Lesson.Status.CANCELLED);
        return lessonRepository.save(lesson);
    }

    /** SCHEDULED -> IN_PROGRESS -> COMPLETED; CANCELLED из любого живого состояния. */
    static void transition(Lesson lesson, Lesson.Status target) {
        Lesson.Status current = lesson.getStatus();
        boolean allowed = switch (target) {
            case IN_PROGRESS -> current == Lesson.Status.SCHEDULED;
            case COMPLETED -> current == Lesson.Status.IN_PROGRESS || current == Lesson.Status.SCHEDULED;
            case CANCELLED -> current == Lesson.Status.SCHEDULED || current == Lesson.Status.IN_PROGRESS;
            default -> false;
        };
        if (!allowed) {
            throw ApiException.conflict("Cannot move lesson from %s to %s".formatted(current.name(), target.name()));
        }
        lesson.setStatus(target);
    }

    private LessonResponse toResponse(Lesson lesson, UUID viewerId) {
        boolean teacherSide = viewerId != null && viewerId.equals(lesson.getTeacherId());
        User other = teacherSide ? lesson.getStudent() : lesson.getTeacher();

        boolean live = lesson.getStatus() == Lesson.Status.SCHEDULED
                || lesson.getStatus() == Lesson.Status.IN_PROGRESS;
        boolean notStartedYet = lesson.getStartAt() == null
                || lesson.getStartAt().isAfter(Instant.now().minusSeconds(3600));

        return new LessonResponse(
                lesson.getId(),
                lesson.getTitle(),
                other != null ? other.getFullName() : null,
                lesson.getStartAt(),
                lesson.getStatus().name(),
                live && notStartedYet && lesson.getStatus() != Lesson.Status.CANCELLED,
                lesson.getBooking() != null ? lesson.getBooking().getId() : null);
    }
}
