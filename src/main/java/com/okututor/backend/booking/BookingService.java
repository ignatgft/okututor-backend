package com.okututor.backend.booking;

import com.okututor.backend.admin.AuditEntry;
import com.okututor.backend.admin.AuditLogService;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.course.Course;
import com.okututor.backend.course.CourseService;
import com.okututor.backend.enrollment.Enrollment;
import com.okututor.backend.enrollment.EnrollmentRepository;
import com.okututor.backend.notification.NotificationService;
import com.okututor.backend.notification.NotificationType;
import com.okututor.backend.user.User;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {

    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public record BookingResponse(
            UUID id,
            UUID course_id,
            String course_title,
            String status,
            Instant date,
            Instant start_at,
            Instant end_at,
            int duration_minutes,
            UUID student_id,
            UUID teacher_id,
            Instant created_at,
            String local_start,
            String timezone
    ) {}

    private final BookingRepository repository;
    private final CourseService courseService;
    private final EnrollmentRepository enrollmentRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    public BookingService(BookingRepository repository, CourseService courseService,
                          EnrollmentRepository enrollmentRepository, NotificationService notificationService,
                          AuditLogService auditLogService) {
        this.repository = repository;
        this.courseService = courseService;
        this.enrollmentRepository = enrollmentRepository;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
    }

    /**
     * фронт шлёт date (yyyy-MM-dd) и time (HH:mm) — локальные часы пользователя;
     * необязательная timezone (IANA) задаёт зону, иначе — UTC. Двойные брони
     * отсекаются частичными уникальными индексами (гонка = DataIntegrityViolationException).
     * Требование «студент только с ACCEPTED заявкой» проверяется на границе контроллера
     * (низкоуровневый метод намеренно не проверяет — см. BookingConcurrencyIT).
     */
    @Transactional
    public BookingResponse create(User student, UUID courseId, UUID enrollmentId,
                                  String date, String time, Integer durationMinutes, String timezone) {
        Course course = courseService.requireById(courseId);
        if (course.getStatus() != Course.Status.APPROVED) {
            throw ApiException.validation("Course is not available for booking");
        }
        User teacher = course.getTeacher();
        if (teacher == null || teacher.getId().equals(student.getId())) {
            throw ApiException.validation("Invalid booking participants");
        }

        Instant start = ScheduleParser.combine(date, time, timezone);
        if (start.isBefore(Instant.now())) {
            throw new com.okututor.backend.common.error.FieldValidationException(
                    java.util.Map.of("date", "Booking time is in the past"));
        }
        int duration = durationMinutes == null ? 60 : durationMinutes;
        ScheduleParser.requireDuration(duration);
        Instant end = start.plusSeconds(duration * 60L);
        ZoneId zone = ScheduleParser.parseZone(timezone);

        Booking booking = new Booking();
        booking.setCourse(course);
        booking.setStudent(student);
        booking.setTeacher(teacher);
        booking.setStartAt(start);
        booking.setEndAt(end);
        booking.setDurationMinutes(duration);
        if (enrollmentId != null) {
            enrollmentRepository.findById(enrollmentId)
                    .filter(e -> e.getStudent() != null && e.getStudent().getId().equals(student.getId()))
                    .ifPresent(booking::setEnrollment);
        }

        try {
            return toResponse(repository.saveAndFlush(booking), zone);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("This slot has already been booked");
        }
    }

    /** бизнес-правило: бронировать урок можно только при принятой заявке (ACCEPTED). */
    @Transactional(readOnly = true)
    public void requireAcceptedEnrollment(UUID studentId, UUID courseId) {
        if (!enrollmentRepository.existsByCourseIdAndStudentIdAndStatus(
                courseId, studentId, Enrollment.Status.ACCEPTED)) {
            throw ApiException.forbidden(
                    "You can book a lesson only after the tutor accepts your request");
        }
    }

    private boolean hasAcceptedEnrollment(UUID studentId, UUID courseId) {
        return enrollmentRepository.existsByCourseIdAndStudentIdAndStatus(
                courseId, studentId, Enrollment.Status.ACCEPTED);
    }

    @Transactional(readOnly = true)
    public Page<BookingResponse> myBookings(UUID studentId, int page, int size) {
        return repository.findByStudentIdOrderByStartAtDesc(studentId, pageable(page, size))
                .map(b -> toResponse(b, ZoneOffset_UTC()));
    }

    @Transactional(readOnly = true)
    public Page<BookingResponse> teacherBookings(UUID teacherId, int page, int size) {
        return repository.findByTeacherIdOrderByStartAtDesc(teacherId, pageable(page, size))
                .map(b -> toResponse(b, ZoneOffset_UTC()));
    }

    @Transactional(readOnly = true)
    public Booking requireById(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Booking not found"));
    }

    @Transactional(readOnly = true)
    public Booking requireParticipantView(UUID id, User viewer) {
        Booking booking = requireById(id);
        boolean admin = viewer.getRole() == com.okututor.backend.user.Role.ADMIN
                || viewer.getRole() == com.okututor.backend.user.Role.SUPER_ADMIN;
        if (!booking.involves(viewer.getId()) && !admin) {
            throw ApiException.forbidden("Not your booking");
        }
        return booking;
    }

    @Transactional(readOnly = true)
    public BookingResponse viewAs(User viewer, UUID id) {
        return toResponse(requireParticipantView(id, viewer), ZoneOffset_UTC());
    }

    @Transactional
    public BookingResponse confirm(User actor, UUID id) {
        Booking booking = requireTeacherAction(id, actor.getId());
        booking.transitionTo(Booking.Status.CONFIRMED);
        Booking saved = repository.save(booking);
        notifyOccupants(saved, NotificationType.BOOKING_CONFIRMED,
                "Занятие по курсу «%s» подтверждено. %s".formatted(courseTitle(saved), humanSlot(saved)),
                "/student/schedule");
        return toResponse(saved, ZoneOffset_UTC());
    }

    @Transactional
    public BookingResponse reject(User actor, UUID id) {
        Booking booking = requireTeacherAction(id, actor.getId());
        booking.transitionTo(Booking.Status.REJECTED);
        Booking saved = repository.save(booking);
        notifyOccupants(saved, NotificationType.BOOKING_REJECTED,
                "Занятие по курсу «%s» отклонено репетитором".formatted(courseTitle(saved)),
                "/student/schedule");
        return toResponse(saved, ZoneOffset_UTC());
    }

    @Transactional
    public BookingResponse cancel(User actor, UUID id) {
        Booking booking = requireById(id);
        if (!booking.involves(actor.getId())
                && actor.getRole() != com.okututor.backend.user.Role.ADMIN
                && actor.getRole() != com.okututor.backend.user.Role.SUPER_ADMIN) {
            throw ApiException.forbidden("Not your booking");
        }
        booking.transitionTo(Booking.Status.CANCELLED);
        Booking saved = repository.save(booking);
        notifyOccupants(saved, NotificationType.BOOKING_CANCELLED,
                "Занятие по курсу «%s» отменено".formatted(courseTitle(saved)),
                "/student/schedule");
        return toResponse(saved, ZoneOffset_UTC());
    }

    @Transactional
    public BookingResponse complete(User actor, UUID id) {
        Booking booking = requireTeacherAction(id, actor.getId());
        booking.transitionTo(Booking.Status.COMPLETED);
        Booking saved = repository.save(booking);
        BookingResponse response = toResponse(saved, ZoneOffset_UTC());
        // если занятие привязано к заявке без расписания — одиночное занятие = заявка COMPLETED
        tryCompleteEnrollment(saved);
        // уведомить студента о завершении занятия (оценка теперь доступна)
        Map<String, Object> payload = bookingPayload(saved);
        notificationService.notify(saved.getStudentId(),
                "Занятие по курсу «%s» завершено — можно оставить отзыв".formatted(courseTitle(saved)),
                NotificationType.BOOKING_COMPLETED,
                "/student/schedule",
                payload);
        return response;
    }

    private void tryCompleteEnrollment(Booking booking) {
        try {
            if (booking.getSchedule() != null) {
                return; // расписанию управляет LessonService
            }
            Enrollment enrollment = booking.getEnrollment();
            if (enrollment == null || enrollment.getStatus() != Enrollment.Status.SCHEDULED) {
                // одиночные брони через accept-and-schedule создают enrollment ACCEPTED, не SCHEDULED — не переводим
                // но если enrollment уже SCHEDULED (будущее) — завершаем
                return;
            }
            String old = enrollment.getStatus().name();
            enrollment.transitionTo(Enrollment.Status.COMPLETED);
            enrollmentRepository.save(enrollment);
            auditLogService.logSync(AuditEntry.of(booking.getTeacherId(), "APPLICATION_COMPLETED", "APPLICATION", enrollment.getId())
                    .withValues(old, Enrollment.Status.COMPLETED.name()));
        } catch (Exception ignored) {
        }
    }

    private void notifyOccupants(Booking booking, String type, String message, String link) {
        Map<String, Object> payload = bookingPayload(booking);
        User student = booking.getStudent();
        User teacher = booking.getTeacher();
        // каждому участнику своя ссылка; для простоты шлём обоим на их расписание
        if (student != null) {
            notificationService.notify(student.getId(), message, type, "/student/schedule", payload);
        }
        if (teacher != null) {
            notificationService.notify(teacher.getId(), message, type, "/tutor/schedule", payload);
        }
    }

    private Map<String, Object> bookingPayload(Booking b) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("booking_id", b.getId());
        payload.put("course_id", b.getCourse() != null ? b.getCourse().getId() : null);
        if (b.getEnrollment() != null) {
            payload.put("enrollment_id", b.getEnrollment().getId());
        }
        payload.put("scheduled_at", b.getStartAt().toString());
        payload.put("duration_minutes", b.getDurationMinutes());
        return payload;
    }

    private static String courseTitle(Booking b) {
        return b.getCourse() != null ? b.getCourse().getTitle() : "занятие";
    }

    private static String humanSlot(Booking b) {
        ZoneId zone = ZoneOffset_UTC();
        String date = DateTimeFormatter.ISO_LOCAL_DATE.format(b.getStartAt().atZone(zone));
        String time = DateTimeFormatter.ofPattern("HH:mm").format(b.getStartAt().atZone(zone));
        return date + " в " + time;
    }

    private Booking requireTeacherAction(UUID bookingId, UUID teacherId) {
        Booking booking = requireById(bookingId);
        if (!teacherId.equals(booking.getTeacherId())) {
            throw ApiException.forbidden("Only the tutor can perform this action");
        }
        return booking;
    }

    private static java.time.ZoneOffset ZoneOffset_UTC() {
        return java.time.ZoneOffset.UTC;
    }

    private static Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    /**
     * @param zone зона для local_start; для списков используется UTC (прежнее поведение),
     *             при создании — зона запроса, чтобы UI не угадывал день.
     */
    private BookingResponse toResponse(Booking b, ZoneId zone) {
        String localStart = zone == null ? null
                : ISO_LOCAL.format(b.getStartAt().atZone(zone));
        return new BookingResponse(
                b.getId(),
                b.getCourse() != null ? b.getCourse().getId() : null,
                b.getCourse() != null ? b.getCourse().getTitle() : null,
                b.getStatus().name(),
                b.getStartAt(),
                b.getStartAt(),
                b.getEndAt(),
                b.getDurationMinutes(),
                b.getStudentId(),
                b.getTeacherId(),
                b.getCreatedAt(),
                localStart,
                zone != null ? zone.getId() : null);
    }
}
