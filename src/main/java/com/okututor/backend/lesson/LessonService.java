package com.okututor.backend.lesson;

import com.okututor.backend.admin.AuditEntry;
import com.okututor.backend.admin.AuditLogService;
import com.okututor.backend.booking.Booking;
import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.booking.ScheduleParser;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.ErrorCodes;
import com.okututor.backend.course.Course;
import com.okututor.backend.course.CourseRepository;
import com.okututor.backend.enrollment.Enrollment;
import com.okututor.backend.enrollment.EnrollmentRepository;
import com.okututor.backend.notification.NotificationService;
import com.okututor.backend.notification.NotificationType;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            Instant end_at,
            String status,
            boolean joinable,
            UUID booking_id,
            UUID schedule_id,
            String cancel_reason,
            String location_type,
            String location_address,
            String location_details
    ) {}

    /** перенос занятия: start_at/end_at — ISO-8601 UTC; длительность строго из допустимого множества. */
    public record RescheduleRequest(Instant start_at, Instant end_at) {}

    private final LessonRepository lessonRepository;
    private final BookingRepository bookingRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final UserService userService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    public LessonService(LessonRepository lessonRepository, BookingRepository bookingRepository,
                         CourseRepository courseRepository, EnrollmentRepository enrollmentRepository,
                         UserService userService,
                         NotificationService notificationService, AuditLogService auditLogService) {
        this.lessonRepository = lessonRepository;
        this.bookingRepository = bookingRepository;
        this.courseRepository = courseRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.userService = userService;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
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

    @Transactional(readOnly = true)
    public Lesson requireParticipantView(UUID id, User viewer) {
        Lesson lesson = requireById(id);
        if (!lesson.involves(viewer.getId()) && !admin(viewer)) {
            throw ApiException.forbidden("Not your lesson");
        }
        return lesson;
    }

    @Transactional
    public Lesson create(User tutor, UUID courseId, UUID studentId, String title, Instant startAt,
                         LocationType locationType, String locationAddress, String locationDetails) {
        User student = userService.requireById(studentId);
        Lesson lesson = new Lesson();
        lesson.setTeacher(tutor);
        lesson.setStudent(student);
        lesson.setTitle(title == null || title.isBlank() ? "Tutoring session" : title.trim());
        lesson.setStartAt(startAt);
        lesson.setLocationType(locationType);
        lesson.setLocationAddress(locationAddress);
        lesson.setLocationDetails(locationDetails);
        if (courseId != null) {
            Course course = courseRepository.findById(courseId)
                    .orElseThrow(() -> ApiException.notFound("Course not found"));
            lesson.setCourse(course);
        }
        return lessonRepository.save(lesson);
    }

    @Transactional
    public void start(User actor, UUID id) {
        Lesson lesson = requireParticipantView(id, actor);
        transition(lesson, Lesson.Status.IN_PROGRESS);
        lessonRepository.save(lesson);
        notifyOccupants(lesson, NotificationType.LESSON_STARTED, "Занятие «%s» началось".formatted(titleOf(lesson)));
        auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_STARTED", "LESSON", lesson.getId()));
    }

    @Transactional
    public void complete(User actor, UUID id) {
        Lesson lesson = requireParticipantView(id, actor);
        transition(lesson, Lesson.Status.COMPLETED);
        lessonRepository.save(lesson);
        // зеркалируем статус в бронь: отзыв студента привязан к Booking
        Booking booking = lesson.getBooking();
        if (booking != null && (booking.getStatus() == Booking.Status.CONFIRMED
                || booking.getStatus() == Booking.Status.RESCHEDULED)) {
            booking.transitionTo(Booking.Status.COMPLETED);
            bookingRepository.save(booking);
        }
        // если все занятия расписания завершены — заявка переходит в COMPLETED
        tryCompleteEnrollment(lesson);
        notifyOccupants(lesson, NotificationType.LESSON_COMPLETED,
                "Занятие «%s» завершено — можно оставить отзыв".formatted(titleOf(lesson)));
        auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_COMPLETED", "LESSON", lesson.getId()));
    }

    private void tryCompleteEnrollment(Lesson lesson) {
        try {
            Enrollment enrollment = null;
            if (lesson.getSchedule() != null) {
                enrollment = lesson.getSchedule().getApplication();
            } else if (lesson.getBooking() != null) {
                enrollment = lesson.getBooking().getEnrollment();
            }
            if (enrollment == null || enrollment.getStatus() != Enrollment.Status.SCHEDULED) {
                return;
            }
            boolean allDone;
            if (lesson.getSchedule() != null) {
                var all = lessonRepository.findByScheduleIdOrderByStartAtAsc(lesson.getSchedule().getId());
                allDone = !all.isEmpty() && all.stream().allMatch(l ->
                        l.getStatus() == Lesson.Status.COMPLETED || l.getStatus() == Lesson.Status.CANCELLED);
            } else {
                // одиночное занятие без расписания — завершение одного урока = завершение заявки
                allDone = true;
            }
            if (allDone) {
                String old = enrollment.getStatus().name();
                enrollment.transitionTo(Enrollment.Status.COMPLETED);
                enrollmentRepository.save(enrollment);
                auditLogService.logSync(com.okututor.backend.admin.AuditEntry.of(
                        lesson.getTeacherId(), "APPLICATION_COMPLETED", "APPLICATION", enrollment.getId())
                        .withValues(old, Enrollment.Status.COMPLETED.name()));
            }
        } catch (Exception ignored) {
        }
    }

    /** отмена с причиной; зеркалируется в связанную бронь (spec §9/§32). */
    @Transactional
    public void cancel(User actor, UUID id, String reason) {
        Lesson lesson = requireParticipantView(id, actor);
        if (!lesson.isLive()) {
            throw ApiException.conflict("Lesson is already finished or cancelled");
        }
        lesson.markCancelled(actor, reason);
        lessonRepository.save(lesson);
        Booking booking = lesson.getBooking();
        if (booking != null && booking.isLive()) {
            booking.markCancelled(actor, reason);
            bookingRepository.save(booking);
        }
        notifyOccupants(lesson, NotificationType.LESSON_CANCELLED,
                "Занятие «%s» отменено".formatted(titleOf(lesson)));
        auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_CANCELLED", "LESSON", lesson.getId())
                .withDetails(reason));
    }

    /**
     * Перенос занятия: новый [start_at, end_at) в UTC; занятость участника
     * перепроверяется (UNIQUE-индексы броней защищают от гонки на commit).
     */
    @Transactional
    public LessonResponse reschedule(User actor, UUID lessonId, RescheduleRequest req) {
        if (req == null || req.start_at() == null) {
            throw ApiException.validation("start_at is required (ISO-8601 UTC)");
        }
        Lesson lesson = requireParticipantView(lessonId, actor);
        if (!lesson.isLive()) {
            throw ApiException.conflict(ErrorCodes.LESSON_CONFLICT, "Cannot reschedule a finished or cancelled lesson");
        }
        Instant start = req.start_at();
        Instant end = req.end_at() != null ? req.end_at() : start.plusSeconds(60 * 60L);
        if (!end.isAfter(start)) {
            throw ApiException.validation("end_at must be after start_at");
        }
        if (start.isBefore(Instant.now())) {
            throw ApiException.validation("start_at is in the past");
        }
        int duration = (int) (end.getEpochSecond() - start.getEpochSecond()) / 60;
        if (duration <= 0 || duration != (int) ((end.getEpochSecond() - start.getEpochSecond()) / 60.0)) {
            throw ApiException.validation("Invalid lesson duration");
        }
        ScheduleParser.requireDuration(duration);
        Instant exactEnd = start.plusSeconds(duration * 60L);

        throwIfConflicts(lesson, start, exactEnd);

        String oldSlot = lesson.getStartAt() == null ? null : lesson.getStartAt().toString();
        lesson.setStartAt(start);
        lesson.setEndAt(exactEnd);
        lessonRepository.save(lesson);

        Booking booking = lesson.getBooking();
        if (booking != null) {
            booking.setStartAt(start);
            booking.setEndAt(exactEnd);
            booking.setDurationMinutes(duration);
            bookingRepository.save(booking);
        }

        notifyOccupants(lesson, NotificationType.LESSON_RESCHEDULED,
                "Занятие «%s» перенесено на %s".formatted(titleOf(lesson), String.valueOf(start)));
        auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_RESCHEDULED", "LESSON", lesson.getId())
                .withValues(oldSlot, start.toString()));
        return toResponse(lesson, actor.getId());
    }

    /** занятость участников урока на интервале (кроме самого переносимого урока/его брони). */
    private void throwIfConflicts(Lesson lesson, Instant from, Instant to) {
        List<Booking.Status> activeBookings = List.of(Booking.Status.PENDING,
                Booking.Status.CONFIRMED, Booking.Status.RESCHEDULED);
        List<Lesson.Status> activeLessons = List.of(Lesson.Status.SCHEDULED, Lesson.Status.IN_PROGRESS);
        Booking booking = lesson.getBooking();
        UUID excludeBooking = booking != null ? booking.getId() : null;

        boolean studentBusy = bookingRepository.overlapsParticipantExcluding(
                lesson.getStudentId(), excludeBooking, activeBookings, from, to);
        boolean teacherBusy = bookingRepository.overlapsParticipantExcluding(
                lesson.getTeacherId(), excludeBooking, activeBookings, from, to);
        if (studentBusy || teacherBusy) {
            throw ApiException.conflict(ErrorCodes.LESSON_CONFLICT,
                    "This time conflicts with an existing lesson of one of the participants");
        }
        boolean lessonBusyStudent = lessonRepository.overlapsParticipantExcluding(
                lesson.getStudentId(), lesson.getId(), activeLessons, from, to);
        boolean lessonBusyTeacher = lessonRepository.overlapsParticipantExcluding(
                lesson.getTeacherId(), lesson.getId(), activeLessons, from, to);
        if (lessonBusyStudent || lessonBusyTeacher) {
            throw ApiException.conflict(ErrorCodes.LESSON_CONFLICT,
                    "This time conflicts with an existing lesson of one of the participants");
        }
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

    private void notifyOccupants(Lesson lesson, String type, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lesson_id", lesson.getId());
        payload.put("course_id", lesson.getCourse() != null ? lesson.getCourse().getId() : null);
        if (lesson.getBooking() != null) {
            payload.put("booking_id", lesson.getBooking().getId());
        }
        if (lesson.getSchedule() != null) {
            payload.put("schedule_id", lesson.getSchedule().getId());
        }
        payload.put("scheduled_at", lesson.getStartAt() != null ? lesson.getStartAt().toString() : null);
        if (lesson.getStudent() != null) {
            notificationService.notify(lesson.getStudentId(), message, type, "/student/schedule", payload,
                    "LESSON", lesson.getId().toString());
        }
        if (lesson.getTeacher() != null) {
            notificationService.notify(lesson.getTeacherId(), message, type, "/tutor/schedule", payload,
                    "LESSON", lesson.getId().toString());
        }
    }

    private static boolean admin(User viewer) {
        return viewer != null && (viewer.getRole() == com.okututor.backend.user.Role.ADMIN
                || viewer.getRole() == com.okututor.backend.user.Role.SUPER_ADMIN);
    }

    private static String titleOf(Lesson lesson) {
        return lesson.getTitle() != null && !lesson.getTitle().isBlank() ? lesson.getTitle() : "Занятие";
    }

    public LessonResponse toResponse(Lesson lesson, UUID viewerId) {
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
                lesson.getEndAt(),
                lesson.getStatus().name(),
                live && notStartedYet && lesson.getStatus() != Lesson.Status.CANCELLED,
                lesson.getBooking() != null ? lesson.getBooking().getId() : null,
                lesson.getSchedule() != null ? lesson.getSchedule().getId() : null,
                lesson.getCancelReason(),
                lesson.getLocationType() != null ? lesson.getLocationType().name() : null,
                lesson.getLocationAddress(),
                lesson.getLocationDetails());
    }
}