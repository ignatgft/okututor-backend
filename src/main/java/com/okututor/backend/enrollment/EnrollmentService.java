package com.okututor.backend.enrollment;

import com.okututor.backend.admin.AuditEntry;
import com.okututor.backend.admin.AuditLogService;
import com.okututor.backend.booking.Booking;
import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.booking.ScheduleParser;
import com.okututor.backend.lesson.Lesson;
import com.okututor.backend.lesson.LessonRepository;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.ErrorCodes;
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
            UUID tutor_id,
            String preferred_format,
            List<String> preferred_days,
            String preferred_start_time,
            String preferred_end_time,
            String frequency,
            Integer duration_minutes,
            Instant expires_at,
            Instant created_at,
            Instant updated_at
    ) {
        public static EnrollmentResponse notRequested() {
            return new EnrollmentResponse(null, "NOT_REQUESTED", null, null, null, null, null, null, null,
                    null, null, List.of(), null, null, null, null, null, null, null);
        }
    }

    public record AcceptAndScheduleRequest(
            String date,
            String time,
            Integer duration_minutes,
            String timezone,
            SeriesRequest series,
            // совместимость с фронтовым ScheduleWizard (плоский payload вместо вложенного series)
            String start_date,
            String end_date,
            List<String> days,
            List<String> weekdays,
            String format,
            String location_type,
            Map<String, Object> location,
            Integer count
    ) {
        public AcceptAndScheduleRequest(String date, String time, Integer duration_minutes, String timezone,
                                        SeriesRequest series) {
            this(date, time, duration_minutes, timezone, series, null, null, null, null, null, null, null, null);
        }
    }

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

    private static final int DEFAULT_EXPIRATION_DAYS = 14;

    private final EnrollmentRepository repository;
    private final CourseService courseService;
    private final BookingRepository bookingRepository;
    private final LessonRepository lessonRepository;
    private final NotificationService notificationService;
    private final MessagingService messagingService;
    private final AvailabilitySlotRepository availabilitySlotRepository;
    private final AuditLogService auditLogService;

    public EnrollmentService(EnrollmentRepository repository, CourseService courseService,
                             BookingRepository bookingRepository, LessonRepository lessonRepository,
                             NotificationService notificationService,
                             MessagingService messagingService,
                             AvailabilitySlotRepository availabilitySlotRepository,
                             AuditLogService auditLogService) {
        this.repository = repository;
        this.courseService = courseService;
        this.bookingRepository = bookingRepository;
        this.lessonRepository = lessonRepository;
        this.notificationService = notificationService;
        this.messagingService = messagingService;
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public EnrollmentResponse enroll(User student, UUID courseId, String message, String preferredSchedule) {
        return enroll(student, courseId, message, preferredSchedule,
                null, List.of(), null, null, null, null);
    }

    /** полная форма заявки с предпочтениями (spec §32 §34): предпочтения — пожелания, не расписание. */
    @Transactional
    public EnrollmentResponse enroll(User student, UUID courseId, String message, String preferredSchedule,
                                     String preferredFormat, List<String> preferredDays,
                                     LocalTime preferredStartTime, LocalTime preferredEndTime,
                                     String frequency, Integer durationMinutes) {
        Course course = courseService.requireById(courseId);
        if (course.getStatus() != Course.Status.APPROVED) {
            throw ApiException.validation("Course is not open for enrollment");
        }
        if (course.getTeacher() != null && course.getTeacher().getId().equals(student.getId())) {
            throw ApiException.conflict("You cannot enroll in your own course");
        }
        boolean hasActive = !repository
                .findByCourseIdAndStudentIdAndStatusIn(courseId, student.getId(), Enrollment.ACTIVE_STATUSES)
                .isEmpty();
        if (hasActive) {
            throw ApiException.conflict("You already have a request for this course");
        }

        List<String> days = normalizeDays(preferredDays);
        validatePrefs(preferredFormat, days, preferredStartTime, preferredEndTime, durationMinutes);

        Enrollment enrollment = new Enrollment();
        enrollment.setCourse(course);
        enrollment.setStudent(student);
        enrollment.setTutor(course.getTeacher());
        enrollment.setMessage(message);
        enrollment.setPreferredSchedule(preferredSchedule);
        enrollment.setPreferredFormat(preferredFormat);
        enrollment.setPreferredDays(days);
        enrollment.setPreferredStartTime(preferredStartTime);
        enrollment.setPreferredEndTime(preferredEndTime);
        enrollment.setFrequency(frequency);
        enrollment.setDurationMinutes(durationMinutes);
        enrollment.setExpiresAt(Instant.now().plusSeconds(DEFAULT_EXPIRATION_DAYS * 86400L));
        Enrollment saved;
        try {
            saved = repository.save(enrollment);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            // гонка: два почти одновременных enroll прошли проверку hasActive,
            // индекс uq_enrollment_active (partial unique) не дал вставить дубль
            throw ApiException.conflict("You already have a request for this course");
        }
        auditLogService.logSync(AuditEntry.of(student.getId(), "APPLICATION_CREATED", "APPLICATION", saved.getId()));
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
        // актуальная заявка студента на курс: сначала активная (уникальна на уровне БД
        // индексом uq_enrollment_active), иначе последняя историческая — findFirst не падает на дублях.
        return repository.findByCourseIdAndStudentIdAndStatusIn(courseId, viewer.getId(), Enrollment.ACTIVE_STATUSES)
                .or(() -> repository.findFirstByCourseIdAndStudentIdOrderByCreatedAtDesc(courseId, viewer.getId()))
                .map(this::toResponse)
                .orElse(EnrollmentResponse.notRequested());
    }

    /** просмотр конкретной заявки: участник (студент или тьютор курса) или ADMIN. */
    @Transactional(readOnly = true)
    public EnrollmentResponse getById(User viewer, UUID enrollmentId) {
        Enrollment enrollment = repository.findById(enrollmentId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.APPLICATION_NOT_FOUND, "Enrollment not found"));
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
        if (!enrollment.studentMayCancel()) {
            throw ApiException.conflict(com.okututor.backend.common.error.ErrorCodes.INVALID_APPLICATION_STATE,
                    "Application is already finished — cancel the schedule or lessons instead");
        }
        String old = enrollment.getStatus().name();
        enrollment.cancel();
        Enrollment saved = repository.save(enrollment);
        auditLogService.logSync(AuditEntry.of(student.getId(), "APPLICATION_CANCELLED", "APPLICATION", saved.getId())
                .withValues(old, Enrollment.Status.CANCELLED.name()));
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
        String old = enrollment.getStatus().name();
        enrollment.transitionTo(Enrollment.Status.ACCEPTED);
        Enrollment saved = repository.save(enrollment);
        auditLogService.logSync(AuditEntry.of(teacher.getId(), "APPLICATION_ACCEPTED", "APPLICATION", saved.getId())
                .withValues(old, Enrollment.Status.ACCEPTED.name()));
        ensureConversation(saved);
        notifyStudentAccepted(saved, null);
        return toResponse(saved);
    }

    @Transactional
    public EnrollmentResponse reject(User teacher, UUID enrollmentId) {
        return reject(teacher, enrollmentId, null);
    }

    @Transactional
    public EnrollmentResponse reject(User teacher, UUID enrollmentId, String reason) {
        Enrollment enrollment = requireTutorDecisionTarget(enrollmentId, teacher.getId());
        String old = enrollment.getStatus().name();
        enrollment.transitionTo(Enrollment.Status.REJECTED);
        Enrollment saved = repository.save(enrollment);
        AuditEntry audit = AuditEntry.of(teacher.getId(), "APPLICATION_REJECTED", "APPLICATION", saved.getId())
                .withValues(old, Enrollment.Status.REJECTED.name());
        auditLogService.logSync(reason == null || reason.isBlank() ? audit : audit.withDetails(reason));
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
            throw ApiException.validation("date and time are required");
        }

        // серия занятий по расписанию (дни недели в диапазоне дат)
        if (req.series() != null) {
            return acceptAndScheduleSeries(teacher, enrollment, req);
        }
        // фронт ScheduleWizard шлёт плоский payload с days/start_date/end_date вместо вложенного series
        List<String> flatDays = req.days() != null && !req.days().isEmpty() ? req.days() : req.weekdays();
        if (flatDays != null && !flatDays.isEmpty()
                && req.start_date() != null && req.end_date() != null) {
            SeriesRequest fakeSeries = new SeriesRequest(
                    req.start_date(), req.end_date(),
                    req.time(), flatDays, req.duration_minutes(), req.timezone());
            AcceptAndScheduleRequest wrapped = new AcceptAndScheduleRequest(
                    req.date(), req.time(), req.duration_minutes(), req.timezone(),
                    fakeSeries, req.start_date(), req.end_date(), req.days(), req.weekdays(),
                    req.format(), req.location_type(), req.location(), req.count());
            return acceptAndScheduleSeries(teacher, enrollment, wrapped);
        }

        // 2. Парсить дату/время в зоне запроса; длительность из допустимого множества
        Instant startAt = ScheduleParser.combine(req.date(), req.time(), req.timezone());
        if (startAt.isBefore(Instant.now())) {
            throw ApiException.validation("Booking time is in the past");
        }
        int duration = req.duration_minutes() == null ? 60 : req.duration_minutes();
        ScheduleParser.requireDuration(duration);
        Instant endAt = startAt.plusSeconds(duration * 60L);

        // 3. Принять заявку (атомарно в одной транзакции с бронированием)
        String old = enrollment.getStatus().name();
        enrollment.transitionTo(Enrollment.Status.ACCEPTED);
        repository.save(enrollment);
        auditLogService.logSync(AuditEntry.of(teacher.getId(), "APPLICATION_ACCEPTED", "APPLICATION", enrollment.getId())
                .withValues(old, Enrollment.Status.ACCEPTED.name()));

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
            throw ApiException.conflict("This time slot is already booked");
        }

        // 4b. Зеркалим в Lesson, чтобы GET /lessons и /calendar отдавали занятие обеим сторонам
        Lesson lesson = new Lesson();
        lesson.setCourse(enrollment.getCourse());
        lesson.setTeacher(teacher);
        lesson.setStudent(enrollment.getStudent());
        lesson.setBooking(booking);
        lesson.setStartAt(startAt);
        lesson.setEndAt(endAt);
        lesson.setStatus(Lesson.Status.SCHEDULED);
        lesson.setSequenceNumber(1);
        lesson.setTitle(enrollment.getCourse() != null ? enrollment.getCourse().getTitle() : "Tutoring session");
        lessonRepository.save(lesson);

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
            throw ApiException.validation("end_date must be on or after start_date");
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
        String old = enrollment.getStatus().name();
        enrollment.transitionTo(Enrollment.Status.ACCEPTED);
        repository.save(enrollment);
        auditLogService.logSync(AuditEntry.of(teacher.getId(), "APPLICATION_ACCEPTED", "APPLICATION", enrollment.getId())
                .withValues(old, Enrollment.Status.ACCEPTED.name()));

        List<Booking> created = new ArrayList<>();
        List<String> conflicted = new ArrayList<>();
        List<Booking.Status> active = List.of(Booking.Status.PENDING, Booking.Status.CONFIRMED);
        DateTimeFormatter dateF = DateTimeFormatter.ISO_LOCAL_DATE;
        UUID studentId = enrollment.getStudent() != null ? enrollment.getStudent().getId() : null;
        UUID teacherId = teacher != null ? teacher.getId() : null;
        java.util.Set<Instant> seenStarts = new java.util.HashSet<>();
        int sequence = 1;

        LocalDate current = startDate;
        // если передан count (total_lessons), генерируем ровно count занятий, даже если endDate ограничен
        Integer targetCount = req.count();
        int guard = 0;
        while (!current.isAfter(endDate) && guard < 366) {
            if (targetCount != null && created.size() >= targetCount) break;
            String weekdayKey = current.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            if (weekdays.contains(current.getDayOfWeek()) && weekdayKey != null) {
                String localDate = dateF.format(current);
                Instant startAt;
                try {
                    startAt = current.atTime(time).atZone(zone).toInstant();
                } catch (DateTimeException e) {
                    conflicted.add(localDate);
                    current = current.plusDays(1);
                    guard++;
                    continue;
                }
                // дубликат внутри батча
                if (!seenStarts.add(startAt)) {
                    conflicted.add(localDate + " (дубликат)");
                    current = current.plusDays(1);
                    guard++;
                    continue;
                }
                Instant endAt = startAt.plusSeconds(duration * 60L);

                // проверки: дата в будущем, доступность тьютора, конфликт преподавателя и ученика
                boolean teacherBusy = teacherId != null
                        && bookingRepository.overlapsTeacher(teacherId, active, startAt, endAt);
                boolean studentBusy = studentId != null
                        && bookingRepository.overlapsStudent(studentId, active, startAt, endAt);

                if (!startAt.isAfter(Instant.now())
                        || !coversZoneAware(slotsByWeekday.get(weekdayKey), current, time, duration, zone)
                        || teacherBusy
                        || studentBusy) {
                    conflicted.add(localDate);
                    current = current.plusDays(1);
                    guard++;
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
            guard++;
        }
        // если count задан и мы не набрали нужное количество, продолжаем за пределами endDate до 366 дней
        while (targetCount != null && created.size() < targetCount && guard < 366) {
            String weekdayKey = current.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            if (weekdays.contains(current.getDayOfWeek())) {
                String localDate = dateF.format(current);
                Instant startAt;
                try {
                    startAt = current.atTime(time).atZone(zone).toInstant();
                } catch (DateTimeException e) {
                    conflicted.add(localDate);
                    current = current.plusDays(1);
                    guard++;
                    continue;
                }
                if (!seenStarts.add(startAt)) {
                    current = current.plusDays(1);
                    guard++;
                    continue;
                }
                Instant endAt = startAt.plusSeconds(duration * 60L);
                boolean teacherBusy = teacherId != null
                        && bookingRepository.overlapsTeacher(teacherId, active, startAt, endAt);
                boolean studentBusy = studentId != null
                        && bookingRepository.overlapsStudent(studentId, active, startAt, endAt);
                if (!startAt.isAfter(Instant.now())
                        || !coversZoneAware(slotsByWeekday.get(weekdayKey), current, time, duration, zone)
                        || teacherBusy
                        || studentBusy) {
                    conflicted.add(localDate);
                } else {
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
            }
            current = current.plusDays(1);
            guard++;
        }

        // единая атомарная вставка (уникальные индексы teacher/student + start_at защищают от гонок на commit)
        List<Booking> saved = bookingRepository.saveAll(created);

        // зеркалим уроки для календаря/GET lessons — атомарно в той же транзакции, без скрытия ошибок
        if (!saved.isEmpty()) {
            List<Lesson> lessons = new java.util.ArrayList<>();
            int seq = 1;
            for (Booking b : saved) {
                Lesson lesson = new Lesson();
                lesson.setCourse(enrollment.getCourse());
                lesson.setTeacher(teacher);
                lesson.setStudent(enrollment.getStudent());
                lesson.setBooking(b);
                lesson.setStartAt(b.getStartAt());
                lesson.setEndAt(b.getEndAt());
                lesson.setStatus(Lesson.Status.SCHEDULED);
                lesson.setSequenceNumber(seq++);
                lesson.setTitle(enrollment.getCourse() != null ? enrollment.getCourse().getTitle() : "Tutoring session");
                lessons.add(lesson);
            }
            lessonRepository.saveAll(lessons);
        }

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
            throw ApiException.validation("weekdays is required for a series");
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
                throw ApiException.validation("Invalid weekday: " + w);
            }
            result.add(d);
        }
        if (result.isEmpty()) {
            throw ApiException.validation("weekdays must contain at least one valid day");
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

    private static boolean coversZoneAware(List<AvailabilitySlot> slots, LocalDate day, LocalTime lessonStart, int duration, ZoneId lessonZone) {
        if (slots == null || slots.isEmpty()) {
            return false;
        }
        Instant lessonStartInstant = day.atTime(lessonStart).atZone(lessonZone).toInstant();
        Instant lessonEndInstant = lessonStartInstant.plusSeconds(duration * 60L);
        for (AvailabilitySlot slot : slots) {
            ZoneId avZone = com.okututor.backend.booking.ScheduleParser.parseZone(slot.getTimezone());
            Instant avStart = day.atTime(slot.getStartTime()).atZone(avZone).toInstant();
            Instant avEnd = day.atTime(slot.getEndTime()).atZone(avZone).toInstant();
            if (!avStart.isAfter(lessonStartInstant) && !avEnd.isBefore(lessonEndInstant)) {
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

    private static ZoneId ZoneIdOf(String timezone) {
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
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.APPLICATION_NOT_FOUND, "Enrollment not found"));
        User courseTeacher = enrollment.getCourse() != null ? enrollment.getCourse().getTeacher() : null;
        if (courseTeacher == null || !teacherId.equals(courseTeacher.getId())) {
            throw ApiException.forbidden("Only the course tutor can decide on this request");
        }
        return enrollment;
    }

    private Enrollment ownedByStudent(UUID enrollmentId, UUID studentId) {
        Enrollment enrollment = repository.findById(enrollmentId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.APPLICATION_NOT_FOUND, "Enrollment not found"));
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

    /** нормализация предпочтений по дням: {понедельник|Monday|1} → MONDAY. */
    private static List<String> normalizeDays(List<String> days) {
        if (days == null || days.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String raw : days) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String value = raw.trim().toUpperCase(Locale.ROOT);
            if (value.matches("\\d{1,2}")) {
                value = DayOfWeek.of(Integer.parseInt(value)).name();
            }
            try {
                result.add(DayOfWeek.valueOf(value).name());
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("Invalid preferred day: " + raw);
            }
        }
        return result;
    }

    private static void validatePrefs(String format, List<String> days,
                                      LocalTime start, LocalTime end, Integer durationMinutes) {
        if (format != null && !format.isBlank()
                && !format.equalsIgnoreCase("ONLINE") && !format.equalsIgnoreCase("OFFLINE")) {
            throw ApiException.validation("preferred_format must be ONLINE or OFFLINE");
        }
        if (start != null && end != null && !end.isAfter(start)) {
            throw ApiException.validation("preferred_end_time must be after preferred_start_time");
        }
        if (durationMinutes != null) {
            ScheduleParser.requireDuration(durationMinutes);
        }
    }

    private EnrollmentResponse toResponse(Enrollment e) {
        Course course = e.getCourse();
        User student = e.getStudent();
        User tutor = e.getTutor() != null ? e.getTutor()
                : (course != null ? course.getTeacher() : null);
        return new EnrollmentResponse(
                e.getId(),
                e.getStatus().name(),
                e.getMessage(),
                e.getPreferredSchedule(),
                course != null ? course.getId() : null,
                course != null ? course.getTitle() : null,
                student != null ? student.getId() : null,
                student != null ? student.getFullName() : null,
                tutor != null ? tutor.getId() : null,
                tutor != null ? tutor.getId() : null,
                e.getPreferredFormat(),
                e.getPreferredDays(),
                e.getPreferredStartTime() != null ? e.getPreferredStartTime().toString() : null,
                e.getPreferredEndTime() != null ? e.getPreferredEndTime().toString() : null,
                e.getFrequency(),
                e.getDurationMinutes(),
                e.getExpiresAt(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}