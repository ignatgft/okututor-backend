package com.okututor.backend.enrollment;

import com.okututor.backend.booking.Booking;
import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.booking.ScheduleParser;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.course.Course;
import com.okututor.backend.course.CourseService;
import com.okututor.backend.messaging.MessagingService;
import com.okututor.backend.notification.NotificationService;
import com.okututor.backend.notification.NotificationType;
import com.okututor.backend.tutors.AvailabilitySlot;
import com.okututor.backend.tutors.AvailabilitySlotRepository;
import com.okututor.backend.user.User;
import java.time.DayOfWeek;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnrollmentService {

    public record EnrollmentResponse(
            UUID id,
            String status,
            String message,
            String preferred_schedule,
            UUID course_id,
            String course_title,
            UUID student_id,
            String student_name,
            UUID teacher_id,
            Instant created_at,
            Instant updated_at
    ) {
        public static EnrollmentResponse notRequested() {
            return new EnrollmentResponse(null, "NOT_REQUESTED", null, null, null, null, null, null, null, null, null);
        }
    }

    public record AcceptAndScheduleRequest(
            String date,
            String time,
            Integer duration_minutes,
            String timezone,
            SeriesRequest series
    ) {}

    /** серия занятий по расписанию: дни недели внутри [start_date, end_date] в одно время. */
    public record SeriesRequest(
            String start_date,
            String end_date,
            String time,
            List<String> weekdays,
            Integer duration_minutes,
            String timezone
    ) {}

    public record AcceptAndScheduleResponse(
            EnrollmentResponse enrollment,
            UUID booking_id,
            String booking_status,
            Instant booking_start_at,
            Integer created_count,
            List<String> conflicted_dates
    ) {}

    private final EnrollmentRepository repository;
    private final CourseService courseService;
    private final BookingRepository bookingRepository;
    private final NotificationService notificationService;
    private final MessagingService messagingService;
    private final AvailabilitySlotRepository availabilitySlotRepository;

    public EnrollmentService(EnrollmentRepository repository, CourseService courseService,
                             BookingRepository bookingRepository, NotificationService notificationService,
                             MessagingService messagingService,
                             AvailabilitySlotRepository availabilitySlotRepository) {
        this.repository = repository;
        this.courseService = courseService;
        this.bookingRepository = bookingRepository;
        this.notificationService = notificationService;
        this.messagingService = messagingService;
        this.availabilitySlotRepository = availabilitySlotRepository;
    }

    @Transactional
    public EnrollmentResponse enroll(User student, UUID courseId, String message, String preferredSchedule) {
        Course course = courseService.requireById(courseId);
        if (course.getStatus() != Course.Status.APPROVED) {
            throw ApiException.validation("Course is not open for enrollment");
        }
        if (course.getTeacher() != null && course.getTeacher().getId().equals(student.getId())) {
            throw ApiException.conflict("You cannot enroll in your own course");
        }
        boolean hasActive = !repository
                .findByCourseIdAndStudentIdAndStatusIn(courseId, student.getId(),
                        java.util.List.of(Enrollment.Status.PENDING, Enrollment.Status.ACCEPTED))
                .isEmpty();
        if (hasActive) {
            throw ApiException.conflict("You already have a request for this course");
        }

        Enrollment enrollment = new Enrollment();
        enrollment.setCourse(course);
        enrollment.setStudent(student);
        enrollment.setMessage(message);
        enrollment.setPreferredSchedule(preferredSchedule);
        Enrollment saved = repository.save(enrollment);
        User teacher = course.getTeacher();
        if (teacher != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("enrollment_id", saved.getId());
            payload.put("course_id", course.getId());
            notificationService.notify(
                teacher.getId(),
                "Новая заявка на курс «" + course.getTitle() + "» от " + student.getFullName(),
                NotificationType.COURSE_APPLICATION,
                "/tutor/dashboard?tab=requests&id=" + saved.getId(),
                payload
            );
        }
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<EnrollmentResponse> myEnrollments(UUID studentId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return repository.findByStudentIdOrderByUpdatedAtDesc(studentId, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<EnrollmentResponse> tutorRequests(UUID teacherId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return repository.findByTeacherId(teacherId, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public EnrollmentResponse forCourse(User viewer, UUID courseId) {
        return repository.findByCourseIdAndStudentIdOrderByCreatedAtDesc(courseId, viewer.getId())
                .map(this::toResponse)
                .orElse(EnrollmentResponse.notRequested());
    }

    /** просмотр конкретной заявки: участник (студент или тьютор курса) или ADMIN. */
    @Transactional(readOnly = true)
    public EnrollmentResponse getById(User viewer, UUID enrollmentId) {
        Enrollment enrollment = repository.findById(enrollmentId)
                .orElseThrow(() -> ApiException.notFound("Enrollment not found"));
        if (!isParticipant(viewer, enrollment) && !isAdmin(viewer)) {
            throw ApiException.forbidden("You have no access to this enrollment");
        }
        return toResponse(enrollment);
    }

    @Transactional
    public void cancel(User student, UUID enrollmentId) {
        Enrollment enrollment = ownedByStudent(enrollmentId, student.getId());
        if (enrollment.getStatus() == Enrollment.Status.CANCELLED) {
            return; // идемпотентно
        }
        enrollment.setStatus(Enrollment.Status.CANCELLED);
        Enrollment saved = repository.save(enrollment);
        User teacher = saved.getCourse() != null ? saved.getCourse().getTeacher() : null;
        if (teacher != null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("enrollment_id", saved.getId());
            payload.put("course_id", saved.getCourse() != null ? saved.getCourse().getId() : null);
            notificationService.notify(
                teacher.getId(),
                "Студент «" + saved.getStudent().getFullName() + "» отменил заявку на курс «"
                        + saved.getCourse().getTitle() + "»",
                NotificationType.APPLICATION_CANCELLED,
                "/tutor/dashboard?tab=requests",
                payload
            );
        }
    }

    @Transactional
    public EnrollmentResponse accept(User teacher, UUID enrollmentId) {
        Enrollment enrollment = requireTutorDecisionTarget(enrollmentId, teacher.getId());
        enrollment.transitionTo(Enrollment.Status.ACCEPTED);
        Enrollment saved = repository.save(enrollment);
        ensureConversation(saved);
        notifyStudentAccepted(saved, null);
        return toResponse(saved);
    }

    @Transactional
    public EnrollmentResponse reject(User teacher, UUID enrollmentId) {
        Enrollment enrollment = requireTutorDecisionTarget(enrollmentId, teacher.getId());
        enrollment.transitionTo(Enrollment.Status.REJECTED);
        Enrollment saved = repository.save(enrollment);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enrollment_id", saved.getId());
        payload.put("course_id", saved.getCourse() != null ? saved.getCourse().getId() : null);
        notificationService.notify(
            saved.getStudent().getId(),
            "Ваша заявка на курс «" + saved.getCourse().getTitle() + "» отклонена",
            NotificationType.APPLICATION_REJECTED,
            "/student/courses",
            payload
        );
        return toResponse(saved);
    }

    @Transactional
    public AcceptAndScheduleResponse acceptAndSchedule(
            User teacher, UUID enrollmentId, AcceptAndScheduleRequest req) {

        // 1. Найти заявку и проверить права
        Enrollment enrollment = requireTutorDecisionTarget(enrollmentId, teacher.getId());

        if (req == null) {
            throw com.okututor.backend.common.error.ApiException.validation("date and time are required");
        }

        // серия занятий по расписанию (дни недели в диапазоне дат)
        if (req.series() != null) {
            return acceptAndScheduleSeries(teacher, enrollment, req);
        }

        // 2. Парсить дату/время в зоне запроса; длительность из допустимого множества
        Instant startAt = ScheduleParser.combine(req.date(), req.time(), req.timezone());
        if (startAt.isBefore(Instant.now())) {
            throw com.okututor.backend.common.error.ApiException.validation("Booking time is in the past");
        }
        int duration = req.duration_minutes() == null ? 60 : req.duration_minutes();
        ScheduleParser.requireDuration(duration);
        Instant endAt = startAt.plusSeconds(duration * 60L);

        // 3. Принять заявку (атомарно в одной транзакции с бронированием)
        enrollment.transitionTo(Enrollment.Status.ACCEPTED);
        repository.save(enrollment);

        // 4. Создать Booking со статусом CONFIRMED сразу
        Booking booking = new Booking();
        booking.setCourse(enrollment.getCourse());
        booking.setStudent(enrollment.getStudent());
        booking.setTeacher(teacher);
        booking.setStartAt(startAt);
        booking.setEndAt(endAt);
        booking.setDurationMinutes(duration);
        booking.setStatus(Booking.Status.CONFIRMED);
        booking.setEnrollment(enrollment);

        try {
            bookingRepository.saveAndFlush(booking);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw com.okututor.backend.common.error.ApiException.conflict("This time slot is already booked");
        }

        // 5. Прямой чат студент↔тьютор, чтобы фронт сразу открыл его
        ensureConversation(enrollment);

        // 6. Уведомить студента (человекочитаемые дата/время)
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enrollment_id", enrollment.getId());
        payload.put("booking_id", booking.getId());
        payload.put("course_id", enrollment.getCourse() != null ? enrollment.getCourse().getId() : null);
        payload.put("scheduled_at", startAt.toString());
        payload.put("duration_minutes", duration);
        notificationService.notify(
            enrollment.getStudent().getId(),
            String.format(
                "Заявка принята. Занятие по курсу «%s»: %s в %s",
                enrollment.getCourse().getTitle(),
                java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.format(startAt.atZone(ZoneIdOf(req.timezone()))),
                java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(startAt.atZone(ZoneIdOf(req.timezone())))
            ),
            NotificationType.APPLICATION_ACCEPTED,
            "/student/schedule",
            payload
        );

        return new AcceptAndScheduleResponse(
            toResponse(enrollment),
            booking.getId(),
            booking.getStatus().name(),
            booking.getStartAt(),
            1,
            List.of()
        );
    }

    /** создание серии занятий после принятия заявки (дни недели в диапазоне дат, в одно время). */
    private AcceptAndScheduleResponse acceptAndScheduleSeries(
            User teacher, Enrollment enrollment, AcceptAndScheduleRequest req) {

        SeriesRequest s = req.series();
        ZoneId zone = ScheduleParser.parseZone(s.timezone() != null ? s.timezone() : req.timezone());
        LocalDate startDate = ScheduleParser.parseDate(s.start_date());
        LocalDate endDate = ScheduleParser.parseDate(s.end_date());
        if (endDate.isBefore(startDate)) {
            throw com.okututor.backend.common.error.ApiException.validation("end_date must be on or after start_date");
        }
        LocalTime time = ScheduleParser.parseTime(s.time());
        int duration = s.duration_minutes() != null ? s.duration_minutes()
                : (req.duration_minutes() != null ? req.duration_minutes() : 60);
        ScheduleParser.requireDuration(duration);

        Set<DayOfWeek> weekdays = parseWeekdays(s.weekdays());

        // индекс слотов доступности тьютора по дню недели (без N+1)
        Map<String, List<AvailabilitySlot>> slotsByWeekday = new HashMap<>();
        if (teacher != null) {
            for (AvailabilitySlot slot : availabilitySlotRepository
                    .findByTutorIdOrderByWeekdayAscStartTimeAsc(teacher.getId())) {
                slotsByWeekday.computeIfAbsent(slot.getWeekday(), k -> new ArrayList<>()).add(slot);
            }
        }

        // принятие заявки атомарно с созданием серии (одна транзакция — нет частичного состояния)
        enrollment.transitionTo(Enrollment.Status.ACCEPTED);
        repository.save(enrollment);

        List<Booking> created = new ArrayList<>();
        List<String> conflicted = new ArrayList<>();
        List<Booking.Status> active = List.of(Booking.Status.PENDING, Booking.Status.CONFIRMED);
        DateTimeFormatter dateF = DateTimeFormatter.ISO_LOCAL_DATE;
        UUID studentId = enrollment.getStudent() != null ? enrollment.getStudent().getId() : null;
        UUID teacherId = teacher != null ? teacher.getId() : null;

        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            String weekdayKey = current.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            if (weekdays.contains(current.getDayOfWeek()) && weekdayKey != null) {
                String localDate = dateF.format(current);
                Instant startAt;
                try {
                    startAt = current.atTime(time).atZone(zone).toInstant();
                } catch (DateTimeException e) {
                    conflicted.add(localDate);
                    current = current.plusDays(1);
                    continue;
                }
                Instant endAt = startAt.plusSeconds(duration * 60L);

                // проверки: дата в будущем, доступность тьютора, конфликт преподавателя и ученика
                boolean teacherBusy = teacherId != null
                        && bookingRepository.overlapsTeacher(teacherId, active, startAt, endAt);
                boolean studentBusy = studentId != null
                        && bookingRepository.overlapsStudent(studentId, active, startAt, endAt);

                if (!startAt.isAfter(Instant.now())
                        || !covers(slotsByWeekday.get(weekdayKey), time, duration)
                        || teacherBusy
                        || studentBusy) {
                    conflicted.add(localDate);
                    current = current.plusDays(1);
                    continue;
                }

                Booking booking = new Booking();
                booking.setCourse(enrollment.getCourse());
                booking.setStudent(enrollment.getStudent());
                booking.setTeacher(teacher);
                booking.setStartAt(startAt);
                booking.setEndAt(endAt);
                booking.setDurationMinutes(duration);
                booking.setStatus(Booking.Status.CONFIRMED);
                booking.setEnrollment(enrollment);
                created.add(booking);
            }
            current = current.plusDays(1);
        }

        // единая атомарная вставка (уникальные индексы teacher/student + start_at защищают от гонок на commit)
        List<Booking> saved = bookingRepository.saveAll(created);

        ensureConversation(enrollment);

        Booking first = saved.isEmpty() ? null : saved.get(0);
        notifyStudentAcceptedSeries(enrollment, time, duration, saved.size(), conflicted);

        return new AcceptAndScheduleResponse(
            toResponse(enrollment),
            first != null ? first.getId() : null,
            first != null ? first.getStatus().name() : "ACCEPTED",
            first != null ? first.getStartAt() : null,
            saved.size(),
            conflicted
        );
    }

    /** разбор дней недели («понедельник», «MONDAY» и т.п.) в DayOfWeek. */
    private static Set<DayOfWeek> parseWeekdays(List<String> weekdays) {
        if (weekdays == null || weekdays.isEmpty()) {
            throw com.okututor.backend.common.error.ApiException.validation("weekdays is required for a series");
        }
        Map<String, DayOfWeek> byName = new HashMap<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            byName.put(d.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT), d);
        }
        Set<DayOfWeek> result = new HashSet<>();
        for (String w : weekdays) {
            if (w == null) {
                continue;
            }
            DayOfWeek d = byName.get(w.trim().toLowerCase(Locale.ROOT));
            if (d == null) {
                throw com.okututor.backend.common.error.ApiException.validation("Invalid weekday: " + w);
            }
            result.add(d);
        }
        if (result.isEmpty()) {
            throw com.okututor.backend.common.error.ApiException.validation("weekdays must contain at least one valid day");
        }
        return result;
    }

    /** true, если какой-то слот доступности полностью покрывает занятие [time, time+duration). */
    private static boolean covers(List<AvailabilitySlot> slots, LocalTime start, int duration) {
        if (slots == null || slots.isEmpty()) {
            return false;
        }
        LocalTime end = start.plusMinutes(duration);
        for (AvailabilitySlot slot : slots) {
            if (!slot.getStartTime().isAfter(start) && !slot.getEndTime().isBefore(end)) {
                return true;
            }
        }
        return false;
    }

    private void notifyStudentAcceptedSeries(Enrollment enrollment, LocalTime time,
                                             int duration, int created, List<String> conflicted) {
        if (enrollment.getStudent() == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enrollment_id", enrollment.getId());
        payload.put("course_id", enrollment.getCourse() != null ? enrollment.getCourse().getId() : null);
        payload.put("created_count", created);
        payload.put("duration_minutes", duration);
        payload.put("conflicted_dates", conflicted);

        String courseTitle = enrollment.getCourse() != null ? enrollment.getCourse().getTitle() : "";
        String msg;
        if (created == 0) {
            msg = "Заявка принята, но занятия серии по курсу «" + courseTitle
                    + "» не созданы из-за конфликтов: " + String.join(", ", conflicted);
        } else if (conflicted.isEmpty()) {
            msg = "Серия занятий по курсу «" + courseTitle + "» создана: " + created
                    + " занятие(й) в " + DateTimeFormatter.ofPattern("HH:mm").format(time) + ".";
        } else {
            msg = "Создано " + created + " занятие(й) серии по курсу «" + courseTitle
                    + "». Не созданы из-за конфликтов: " + String.join(", ", conflicted);
        }
        notificationService.notify(
            enrollment.getStudent().getId(),
            msg,
            NotificationType.APPLICATION_ACCEPTED,
            "/student/schedule",
            payload
        );
    }

    private static java.time.ZoneId ZoneIdOf(String timezone) {
        return ScheduleParser.parseZone(timezone);
    }

    private void notifyStudentAccepted(Enrollment enrollment, Booking booking) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enrollment_id", enrollment.getId());
        payload.put("course_id", enrollment.getCourse() != null ? enrollment.getCourse().getId() : null);
        if (booking != null) {
            payload.put("booking_id", booking.getId());
            payload.put("scheduled_at", booking.getStartAt().toString());
            payload.put("duration_minutes", booking.getDurationMinutes());
        }
        notificationService.notify(
            enrollment.getStudent().getId(),
            "Заявка на курс «" + enrollment.getCourse().getTitle() + "» принята",
            NotificationType.APPLICATION_ACCEPTED,
            "/student/courses",
            payload
        );
    }

    /** создаёт DIRECT-переписку студент↔тьютор после принятия заявки, если её ещё нет. */
    private void ensureConversation(Enrollment enrollment) {
        User student = enrollment.getStudent();
        User teacher = enrollment.getCourse() != null ? enrollment.getCourse().getTeacher() : null;
        if (student != null && teacher != null && !student.getId().equals(teacher.getId())) {
            messagingService.openWith(student, teacher.getId());
        }
    }

    private Enrollment requireTutorDecisionTarget(UUID enrollmentId, UUID teacherId) {
        Enrollment enrollment = repository.findById(enrollmentId)
                .orElseThrow(() -> ApiException.notFound("Enrollment not found"));
        User courseTeacher = enrollment.getCourse() != null ? enrollment.getCourse().getTeacher() : null;
        if (courseTeacher == null || !teacherId.equals(courseTeacher.getId())) {
            throw ApiException.forbidden("Only the course tutor can decide on this request");
        }
        return enrollment;
    }

    private Enrollment ownedByStudent(UUID enrollmentId, UUID studentId) {
        Enrollment enrollment = repository.findById(enrollmentId)
                .orElseThrow(() -> ApiException.notFound("Enrollment not found"));
        if (enrollment.getStudent() == null || !studentId.equals(enrollment.getStudent().getId())) {
            throw ApiException.forbidden("Not your enrollment");
        }
        return enrollment;
    }

    private boolean isParticipant(User viewer, Enrollment enrollment) {
        if (viewer == null) {
            return false;
        }
        User student = enrollment.getStudent();
        if (student != null && viewer.getId().equals(student.getId())) {
            return true;
        }
        Course course = enrollment.getCourse();
        return course != null && course.getTeacher() != null && viewer.getId().equals(course.getTeacher().getId());
    }

    private static boolean isAdmin(User viewer) {
        return viewer != null && (viewer.getRole() == com.okututor.backend.user.Role.ADMIN
                || viewer.getRole() == com.okututor.backend.user.Role.SUPER_ADMIN);
    }

    private EnrollmentResponse toResponse(Enrollment e) {
        Course course = e.getCourse();
        User student = e.getStudent();
        User teacher = course != null ? course.getTeacher() : null;
        return new EnrollmentResponse(
                e.getId(),
                e.getStatus().name(),
                e.getMessage(),
                e.getPreferredSchedule(),
                course != null ? course.getId() : null,
                course != null ? course.getTitle() : null,
                student != null ? student.getId() : null,
                student != null ? student.getFullName() : null,
                teacher != null ? teacher.getId() : null,
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
