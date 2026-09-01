package com.okututor.backend.schedule;

import com.okututor.backend.admin.AuditEntry;
import com.okututor.backend.admin.AuditLogService;
import com.okututor.backend.booking.Booking;
import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.booking.ScheduleParser;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.ErrorCodes;
import com.okututor.backend.course.Course;
import com.okututor.backend.enrollment.ApplicationWorkflowService;
import com.okututor.backend.enrollment.Enrollment;
import com.okututor.backend.enrollment.EnrollmentRepository;
import com.okututor.backend.lesson.Lesson;
import com.okututor.backend.lesson.LessonRepository;
import com.okututor.backend.lesson.LessonService;
import com.okututor.backend.lesson.LocationType;
import com.okututor.backend.notification.NotificationService;
import com.okututor.backend.notification.NotificationType;
import com.okututor.backend.tutors.AvailabilitySlot;
import com.okututor.backend.tutors.AvailabilitySlotRepository;
import com.okututor.backend.user.User;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Согласование регулярного расписания по подтверждённой заявке:
 * tutor предложение → студент принимает / отклоняет / контрпредложение.
 * После подтверждения материализуются конкретные встречи (Booking + связанный Lesson)
 * по дням недели на интервале [start_date, end_date], конфликтные даты пропускаются.
 */
@Service
public class ScheduleService {

    public record SlotRequest(String weekday, String start_time, String end_time) {}
    public record ProposeRequest(
            String timezone,
            String format,
            String start_date,
            String end_date,
            Integer duration_minutes,
            List<SlotRequest> slots,
            String message,
            String location_type,
            String location_address,
            String location_details
    ) {}
    public record SlotResponse(String weekday, String start_time, String end_time) {}

    public record ScheduleProposalResponse(
            UUID id,
            UUID schedule_id,
            UUID application_id,
            UUID created_by,
            String created_by_name,
            String created_by_role,
            String status,
            String timezone,
            String start_date,
            String end_date,
            Integer duration_minutes,
            String message,
            List<SlotResponse> slots,
            Instant created_at
    ) {}

    public record ScheduleResponse(
            UUID id,
            UUID application_id,
            UUID course_id,
            String course_title,
            UUID student_id,
            String student_name,
            UUID tutor_id,
            String tutor_name,
            String format,
            String location_type,
            String location_address,
            String location_details,
            String start_date,
            String end_date,
            String timezone,
            String frequency,
            Integer duration_minutes,
            String status,
            List<SlotResponse> slots,
            Integer booked_count,
            Integer conflicted_count,
            Instant created_at,
            Instant updated_at
    ) {}

    public record AcceptResponse(
            ScheduleResponse schedule,
            Integer created_count,
            List<String> conflicted_dates,
            List<UUID> booking_ids
    ) {}

    public record AvailableSlotResponse(String date, String start_time, String end_time, String source) {}

    private final ScheduleRepository scheduleRepository;
    private final ScheduleProposalRepository proposalRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final BookingRepository bookingRepository;
    private final LessonRepository lessonRepository;
    private final AvailabilitySlotRepository availabilitySlotRepository;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final ApplicationWorkflowService workflowService;
    private final LessonService lessonService;

    public ScheduleService(ScheduleRepository scheduleRepository,
                           ScheduleProposalRepository proposalRepository,
                           EnrollmentRepository enrollmentRepository,
                           BookingRepository bookingRepository,
                           LessonRepository lessonRepository,
                           AvailabilitySlotRepository availabilitySlotRepository,
                           NotificationService notificationService,
                           AuditLogService auditLogService,
                           ApplicationWorkflowService workflowService,
                           LessonService lessonService) {
        this.scheduleRepository = scheduleRepository;
        this.proposalRepository = proposalRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.bookingRepository = bookingRepository;
        this.lessonRepository = lessonRepository;
        this.availabilitySlotRepository = availabilitySlotRepository;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
        this.workflowService = workflowService;
        this.lessonService = lessonService;
    }

    // ---------------- propose ----------------

    /** тьютор предлагает расписание; создаёт/обновляет Schedule (PROPOSED) и историю-предложение. */
    @Transactional
    public ScheduleProposalResponse propose(User tutor, UUID applicationId, ProposeRequest req) {
        Enrollment app = requireApplication(applicationId);
        requireCourseTutor(app, tutor.getId());
        if (app.getStatus() != Enrollment.Status.ACCEPTED
                && app.getStatus() != Enrollment.Status.SCHEDULE_PENDING
                && app.getStatus() != Enrollment.Status.SCHEDULE_PROPOSED) {
            throw ApiException.conflict(ErrorCodes.INVALID_APPLICATION_STATE,
                    "A schedule can only be proposed for an accepted application");
        }
        if (proposalRepository.existsByApplicationIdAndStatus(applicationId, ScheduleProposal.Status.PENDING)) {
            throw ApiException.conflict(ErrorCodes.SCHEDULE_NOT_AVAILABLE,
                    "There is already a pending schedule proposal for this application");
        }
        ParsedProposal parsed = parse(req);

        Schedule schedule = scheduleRepository.findByApplicationId(applicationId).orElse(null);
        if (schedule == null) {
            schedule = new Schedule();
            schedule.setApplication(app);
            schedule.setCourse(app.getCourse());
            schedule.setStudent(app.getStudent());
            schedule.setTutor(app.getTutor() != null ? app.getTutor() : tutorOf(app));
        }
        schedule.setStatus(Schedule.Status.PROPOSED);
        schedule.setTimezone(parsed.zone().getId());
        schedule.setStartDate(parsed.startDate());
        schedule.setEndDate(parsed.endDate());
        schedule.setDurationMinutes(parsed.duration());
        schedule.setFormat(parsed.format());
        schedule.setLocationType(parsed.locationType());
        schedule.setLocationAddress(parsed.locationAddress());
        schedule.setLocationDetails(parsed.locationDetails());
        schedule.getSlots().clear();
        for (SlotRequest sr : req.slots()) {
            ScheduleSlot slot = new ScheduleSlot();
            slot.setSchedule(schedule);
            slot.setWeekday(parseWeekday(sr.weekday()));
            slot.setStartTime(ScheduleParser.parseTime(sr.start_time()));
            slot.setEndTime(ScheduleParser.parseTime(sr.end_time()));
            schedule.getSlots().add(slot);
        }
        scheduleRepository.save(schedule);

        ScheduleProposal proposal = new ScheduleProposal();
        proposal.setApplication(app);
        proposal.setSchedule(schedule);
        proposal.setCreatedBy(tutor);
        proposal.setStatus(ScheduleProposal.Status.PENDING);
        proposal.setTimezone(parsed.zone().getId());
        proposal.setStartDate(parsed.startDate());
        proposal.setEndDate(parsed.endDate());
        proposal.setDurationMinutes(parsed.duration());
        proposal.setMessage(req == null ? null : req.message());
        for (SlotRequest sr : req.slots()) {
            ScheduleProposalSlot slot = new ScheduleProposalSlot();
            slot.setProposal(proposal);
            slot.setWeekday(parseWeekday(sr.weekday()));
            slot.setStartTime(ScheduleParser.parseTime(sr.start_time()));
            slot.setEndTime(ScheduleParser.parseTime(sr.end_time()));
            proposal.getSlots().add(slot);
        }
        ScheduleProposal savedProposal = proposalRepository.save(proposal);

        workflowService.transitionTo(app, Enrollment.Status.SCHEDULE_PROPOSED, tutor, "SCHEDULE_PROPOSED");
        app.setTutor(app.getTutor() != null ? app.getTutor() : tutor);
        enrollmentRepository.save(app);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enrollment_id", app.getId());
        payload.put("schedule_id", schedule.getId());
        payload.put("course_id", app.getCourse() != null ? app.getCourse().getId() : null);
        notificationService.notify(app.getStudent().getId(),
                "Тьютор предложил расписание занятий по курсу «" + courseTitle(app) + "»",
                NotificationType.SCHEDULE_PROPOSED,
                "/student/application?id=" + app.getId(),
                payload, "APPLICATION", app.getId().toString());

        return toProposalResponse(savedProposal);
    }

    // ---------------- proposals ----------------

    @Transactional(readOnly = true)
    public List<ScheduleProposalResponse> listProposals(User viewer, UUID applicationId) {
        requireApplicationParticipant(applicationId, viewer);
        return proposalRepository.findByApplicationIdOrderByCreatedAtDesc(applicationId).stream()
                .sorted((a, b) -> {
                    boolean aPending = a.getStatus() == ScheduleProposal.Status.PENDING;
                    boolean bPending = b.getStatus() == ScheduleProposal.Status.PENDING;
                    if (aPending != bPending) {
                        return aPending ? -1 : 1;
                    }
                    Instant ca = a.getCreatedAt();
                    Instant cb = b.getCreatedAt();
                    if (ca == null && cb == null) return 0;
                    if (ca == null) return 1;
                    if (cb == null) return -1;
                    return cb.compareTo(ca);
                })
                .map(this::toProposalResponse).toList();
    }

    @Transactional(readOnly = true)
    public ScheduleProposalResponse getProposal(User viewer, UUID proposalId) {
        ScheduleProposal proposal = requireProposal(proposalId);
        requireApplicationParticipant(proposal.getApplication().getId(), viewer);
        return toProposalResponse(proposal);
    }

    // ---------------- accept/reject/counter ----------------

    /** студент подтверждает расписание → одновременно материализуются конкретные занятия. Идемпотентно. */
    @Transactional
    public AcceptResponse accept(User student, UUID proposalId) {
        ScheduleProposal proposal = requireProposal(proposalId);
        Enrollment app = proposal.getApplication();
        requireApplicationOwner(app, student.getId());

        // идемпотентность: повторный accept уже принятого предложения не меняет состояние
        if (proposal.getStatus() == ScheduleProposal.Status.ACCEPTED) {
            Schedule schedule = proposal.getSchedule();
            if (schedule == null) {
                throw ApiException.conflict(ErrorCodes.SCHEDULE_NOT_AVAILABLE,
                        "The proposed schedule has no slots");
            }
            List<UUID> ids = existingBookings(schedule);
            if (!ids.isEmpty()) {
                schedule.setStatus(Schedule.Status.CONFIRMED);
                scheduleRepository.save(schedule);
            }
            return toAcceptResponse(schedule, 0, List.of(), ids);
        }
        if (proposal.getStatus() != ScheduleProposal.Status.PENDING) {
            throw ApiException.conflict(ErrorCodes.INVALID_APPLICATION_STATE,
                    "This schedule proposal is no longer pending");
        }
        if (app.getStatus() != Enrollment.Status.SCHEDULE_PROPOSED
                && app.getStatus() != Enrollment.Status.SCHEDULED) {
            throw ApiException.conflict(ErrorCodes.INVALID_APPLICATION_STATE,
                    "The application is not awaiting schedule confirmation");
        }
        // already confirmed via previous accept (enrollment already SCHEDULED) → idempotent short-circuit
        if (app.getStatus() == Enrollment.Status.SCHEDULED
                && proposal.getStatus() == ScheduleProposal.Status.PENDING
                && bookingRepository.existsByScheduleId(proposal.getSchedule() != null ? proposal.getSchedule().getId() : null)) {
            Schedule schedule = proposal.getSchedule();
            schedule.setStatus(Schedule.Status.CONFIRMED);
            scheduleRepository.save(schedule);
            return toAcceptResponse(schedule, 0, List.of(), existingBookings(schedule));
        }
        Schedule schedule = proposal.getSchedule();
        if (schedule == null || schedule.getSlots().isEmpty()) {
            throw ApiException.conflict(ErrorCodes.SCHEDULE_NOT_AVAILABLE,
                    "The proposed schedule has no slots");
        }
        if (bookingRepository.existsByScheduleId(schedule.getId())) {
            // повторный клик accept: расписание уже подтверждено и занятия созданы — идемпотентно
            schedule.setStatus(Schedule.Status.CONFIRMED);
            scheduleRepository.save(schedule);
            return toAcceptResponse(schedule, 0, List.of(), existingBookings(schedule));
        }

        proposal.setStatus(ScheduleProposal.Status.ACCEPTED);
        proposalRepository.save(proposal);
        proposalRepository.findByApplicationIdAndStatus(app.getId(), ScheduleProposal.Status.PENDING)
                .forEach(p -> {
                    p.setStatus(ScheduleProposal.Status.SUPERSEDED);
                    proposalRepository.save(p);
                });

        schedule.setStatus(Schedule.Status.CONFIRMED);
        scheduleRepository.save(schedule);

        workflowService.transitionTo(app, Enrollment.Status.SCHEDULED, student, "SCHEDULE_CONFIRMED");

        Generated generated = generateLessons(schedule);

        // уведомление обеим сторонам
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enrollment_id", app.getId());
        payload.put("schedule_id", schedule.getId());
        payload.put("course_id", app.getCourse() != null ? app.getCourse().getId() : null);
        payload.put("created_count", generated.created());
        payload.put("conflicted_dates", generated.conflicted());
        String summary = generated.created() == 0
                ? "Расписание подтверждено, но занятия не созданы из-за конфликтов: "
                        + String.join(", ", generated.conflicted())
                : "Расписание подтверждено: создано " + generated.created() + " занятие(й)"
                        + (generated.conflicted().isEmpty() ? "" : ". Не созданы: " + String.join(", ", generated.conflicted()));
        User tutor = tutorOf(app);
        notificationService.notify(app.getStudent().getId(), summary, NotificationType.SCHEDULE_CONFIRMED,
                "/student/schedule", payload, "APPLICATION", app.getId().toString());
        if (tutor != null) {
            notificationService.notify(tutor.getId(),
                    "Студент подтвердил расписание по курсу «" + courseTitle(app) + "». " + summary,
                    NotificationType.SCHEDULE_CONFIRMED,
                    "/tutor/schedule",
                    payload, "APPLICATION", app.getId().toString());
        }

        return toAcceptResponse(schedule, generated.created(), generated.conflicted(), generated.bookings());
    }

    /** студент отклоняет предложение; заявка возвращается в SCHEDULE_PENDING (ожидание нового). */
    @Transactional
    public ScheduleProposalResponse reject(User student, UUID proposalId) {
        ScheduleProposal proposal = requireProposal(proposalId);
        Enrollment app = proposal.getApplication();
        requireApplicationOwner(app, student.getId());
        if (proposal.getStatus() != ScheduleProposal.Status.PENDING) {
            throw ApiException.conflict(ErrorCodes.INVALID_APPLICATION_STATE,
                    "This schedule proposal is no longer pending");
        }
        proposal.setStatus(ScheduleProposal.Status.REJECTED);
        proposalRepository.save(proposal);

        workflowService.transitionTo(app, Enrollment.Status.SCHEDULE_PENDING, student, "SCHEDULE_REJECTED");
        if (proposal.getSchedule() != null) {
            Schedule schedule = proposal.getSchedule();
            schedule.setStatus(Schedule.Status.PROPOSED);
            scheduleRepository.save(schedule);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enrollment_id", app.getId());
        payload.put("course_id", app.getCourse() != null ? app.getCourse().getId() : null);
        User tutor = tutorOf(app);
        if (tutor != null) {
            notificationService.notify(tutor.getId(),
                    "Студент отклонил предложенное расписание по курсу «" + courseTitle(app) + "»",
                    NotificationType.SCHEDULE_CHANGED,
                    "/tutor/dashboard?tab=requests&id=" + app.getId(),
                    payload, "APPLICATION", app.getId().toString());
        }
        return toProposalResponse(proposal);
    }

    /** студент предлагает встречный вариант: старое предложение → SUPERSEDED, создаётся новое. */
    @Transactional
    public ScheduleProposalResponse counter(User student, UUID proposalId, ProposeRequest req) {
        ScheduleProposal current = requireProposal(proposalId);
        Enrollment app = current.getApplication();
        requireApplicationOwner(app, student.getId());
        if (current.getStatus() != ScheduleProposal.Status.PENDING) {
            throw ApiException.conflict(ErrorCodes.INVALID_APPLICATION_STATE,
                    "This schedule proposal is no longer pending");
        }

        ParsedProposal parsed = parse(req);
        current.setStatus(ScheduleProposal.Status.SUPERSEDED);
        proposalRepository.save(current);

        Schedule schedule = current.getSchedule() != null ? current.getSchedule() : buildSchedule(app, parsed);
        schedule.setStatus(Schedule.Status.PROPOSED);
        schedule.setTimezone(parsed.zone().getId());
        schedule.setStartDate(parsed.startDate());
        schedule.setEndDate(parsed.endDate());
        schedule.setDurationMinutes(parsed.duration());
        schedule.setFormat(parsed.format());
        schedule.setLocationType(parsed.locationType());
        schedule.setLocationAddress(parsed.locationAddress());
        schedule.setLocationDetails(parsed.locationDetails());
        schedule.getSlots().clear();
        for (SlotRequest sr : req.slots()) {
            ScheduleSlot slot = new ScheduleSlot();
            slot.setSchedule(schedule);
            slot.setWeekday(parseWeekday(sr.weekday()));
            slot.setStartTime(ScheduleParser.parseTime(sr.start_time()));
            slot.setEndTime(ScheduleParser.parseTime(sr.end_time()));
            schedule.getSlots().add(slot);
        }
        scheduleRepository.save(schedule);

        ScheduleProposal proposal = new ScheduleProposal();
        proposal.setApplication(app);
        proposal.setSchedule(schedule);
        proposal.setCreatedBy(student);
        proposal.setStatus(ScheduleProposal.Status.PENDING);
        proposal.setTimezone(parsed.zone().getId());
        proposal.setStartDate(parsed.startDate());
        proposal.setEndDate(parsed.endDate());
        proposal.setDurationMinutes(parsed.duration());
        proposal.setMessage(req == null ? null : req.message());
        for (SlotRequest sr : req.slots()) {
            ScheduleProposalSlot slot = new ScheduleProposalSlot();
            slot.setProposal(proposal);
            slot.setWeekday(parseWeekday(sr.weekday()));
            slot.setStartTime(ScheduleParser.parseTime(sr.start_time()));
            slot.setEndTime(ScheduleParser.parseTime(sr.end_time()));
            proposal.getSlots().add(slot);
        }
        ScheduleProposal savedProposal = proposalRepository.save(proposal);

        workflowService.transitionTo(app, Enrollment.Status.SCHEDULE_PROPOSED, student, "SCHEDULE_COUNTER");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enrollment_id", app.getId());
        payload.put("schedule_id", schedule.getId());
        payload.put("course_id", app.getCourse() != null ? app.getCourse().getId() : null);
        User tutor = tutorOf(app);
        if (tutor != null) {
            notificationService.notify(tutor.getId(),
                    "Студент предложил встречный вариант расписания по курсу «" + courseTitle(app) + "»",
                    NotificationType.SCHEDULE_PROPOSED,
                    "/tutor/dashboard?tab=requests&id=" + app.getId(),
                    payload, "APPLICATION", app.getId().toString());
        }
        return toProposalResponse(savedProposal);
    }

    // ---------------- schedules ----------------

    @Transactional(readOnly = true)
    public List<ScheduleResponse> mySchedules(User viewer) {
        List<Schedule> schedules = viewer.getRole() == com.okututor.backend.user.Role.STUDENT
                ? scheduleRepository.findByStudentIdOrderByUpdatedAtDesc(viewer.getId())
                : scheduleRepository.findByTutorIdOrderByUpdatedAtDesc(viewer.getId());
        return schedules.stream().map(s -> toScheduleResponse(s, viewer.getId())).toList();
    }

    @Transactional(readOnly = true)
    public ScheduleResponse getById(User viewer, UUID scheduleId) {
        Schedule schedule = requireSchedule(scheduleId);
        requireScheduleParticipant(schedule, viewer);
        return toScheduleResponse(schedule, viewer.getId());
    }

    /** конкретные занятия расписания (для фронта расписания занятий). */
    @Transactional(readOnly = true)
    public List<LessonService.LessonResponse> lessons(User viewer, UUID scheduleId) {
        requireScheduleParticipant(requireSchedule(scheduleId), viewer);
        return lessonRepository.findByScheduleIdOrderByStartAtAsc(scheduleId).stream()
                .map(l -> lessonService.toResponse(l, viewer.getId()))
                .toList();
    }

    /**
     * Свободные окна для заявки: пересечение предпочтений студента и доступности тьютора
     * в диапазоне дат, минус уже занятые слоты (брони/уроки участников).
     */
    @Transactional(readOnly = true)
    public List<AvailableSlotResponse> availableSlots(User viewer, UUID applicationId,
                                                      String fromDate, String toDate, String timezone) {
        Enrollment app = requireApplicationParticipant(applicationId, viewer);
        LocalDate from = ScheduleParser.parseDate(fromDate);
        LocalDate to = ScheduleParser.parseDate(toDate);
        if (to.isBefore(from)) {
            throw ApiException.validation(ErrorCodes.INVALID_DATE, "to must be on or after from");
        }
        Instant now = Instant.now();

        List<AvailabilitySlot> availability = app.getTutor() != null
                ? availabilitySlotRepository.findByTutorIdOrderByWeekdayAscStartTimeAsc(app.getTutor().getId())
                : List.of();
        Map<String, List<AvailabilitySlot>> byWeekday = new HashMap<>();
        for (AvailabilitySlot slot : availability) {
            byWeekday.computeIfAbsent(slot.getWeekday(), k -> new ArrayList<>()).add(slot);
        }

        List<Booking.Status> activeBookings = List.of(Booking.Status.PENDING,
                Booking.Status.CONFIRMED, Booking.Status.RESCHEDULED);
        List<Lesson.Status> activeLessons = List.of(Lesson.Status.SCHEDULED, Lesson.Status.IN_PROGRESS);

        List<AvailableSlotResponse> result = new ArrayList<>();
        UUID tutorId = tutorOf(app) != null ? tutorOf(app).getId() : null;
        LocalDate day = from;
        while (!day.isAfter(to)) {
            String caption = day.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            for (AvailabilitySlot slot : byWeekday.getOrDefault(caption, List.of())) {
                LocalTime start = effectiveStart(app, slot);
                LocalTime end = effectiveEnd(app, slot);
                if (start == null || !end.isAfter(start)) {
                    continue;
                }
                Instant startInUtc = day.atTime(start).atZone(dayZone(app, timezone)).toInstant();
                Instant endInUtc = day.atTime(end).atZone(dayZone(app, timezone)).toInstant();
                if (startInUtc.isBefore(now)) {
                    continue;
                }
                if (bookingRepository.overlapsStudent(app.getStudent().getId(), activeBookings, startInUtc, endInUtc)
                        || (tutorId != null
                                && bookingRepository.overlapsTeacher(tutorId, activeBookings, startInUtc, endInUtc))
                        || lessonRepository.overlapsParticipant(app.getStudent().getId(), activeLessons, startInUtc, endInUtc)
                        || (tutorId != null
                                && lessonRepository.overlapsParticipant(tutorId, activeLessons, startInUtc, endInUtc))) {
                    continue;
                }
                result.add(new AvailableSlotResponse(DateTimeFormatter.ISO_LOCAL_DATE.format(day),
                        start.toString(), end.toString(),
                        app.getPreferredStartTime() != null ? "PREFERENCE" : "AVAILABILITY"));
            }
            day = day.plusDays(1);
        }
        result.sort(Comparator.comparing(AvailableSlotResponse::date).thenComparing(AvailableSlotResponse::start_time));
        return result;
    }

    // ---------------- generation ----------------

    private record Generated(int created, List<String> conflicted, List<UUID> bookings) {}

    /** материализация встреч: Booking (CONFIRMED) + связанный Lesson (SCHEDULED) по слотам расписания. */
    @Transactional
    public Generated generateLessons(Schedule schedule) {
        ZoneId zone = ScheduleParser.parseZone(schedule.getTimezone());
        int duration = schedule.getDurationMinutes();
        List<Booking.Status> active = List.of(Booking.Status.PENDING,
                Booking.Status.CONFIRMED, Booking.Status.RESCHEDULED);
        List<Lesson.Status> activeLessons = List.of(Lesson.Status.SCHEDULED, Lesson.Status.IN_PROGRESS);
        Instant now = Instant.now();

        Enrollment app = schedule.getApplication();
        User student = schedule.getStudent();
        User tutor = schedule.getTutor();
        UUID studentId = schedule.getStudentId();
        UUID teacherId = schedule.getTutorId();
        Course course = schedule.getCourse();

        Map<String, List<AvailabilitySlot>> availabilityByWeekday = new HashMap<>();
        if (teacherId != null) {
            for (AvailabilitySlot slot : availabilitySlotRepository
                    .findByTutorIdOrderByWeekdayAscStartTimeAsc(teacherId)) {
                availabilityByWeekday.computeIfAbsent(slot.getWeekday(), k -> new ArrayList<>()).add(slot);
            }
        }

        Map<DayOfWeek, List<ScheduleSlot>> slotsByDay = new HashMap<>();
        for (ScheduleSlot slot : schedule.getSlots()) {
            slotsByDay.computeIfAbsent(slot.getWeekday(), k -> new ArrayList<>()).add(slot);
        }

        List<Booking> bookings = new ArrayList<>();
        List<Lesson> lessons = new ArrayList<>();
        List<String> conflicted = new ArrayList<>();
        List<Booking.Status> createdStatuses = new ArrayList<>();

        LocalDate day = schedule.getStartDate();
        while (!day.isAfter(schedule.getEndDate())) {
            String caption = day.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            for (ScheduleSlot slot : slotsByDay.getOrDefault(day.getDayOfWeek(), List.of())) {
                LocalDate localDate = day;
                Instant startAt;
                try {
                    startAt = day.atTime(slot.getStartTime()).atZone(zone).toInstant();
                } catch (java.time.DateTimeException e) {
                    conflicted.add(localDate + " " + slot.getStartTime());
                    continue;
                }
                Instant endAt = startAt.plusSeconds(duration * 60L);
                if (startAt.isBefore(now)) {
                    conflicted.add(localDate + " (в прошлом)");
                    continue;
                }
                if (!covers(availabilityByWeekday.get(caption), slot.getStartTime(), duration)) {
                    conflicted.add(localDate + " (нет доступности)");
                    continue;
                }
                boolean teacherBusy = teacherId != null
                        && (bookingRepository.overlapsTeacher(teacherId, active, startAt, endAt)
                            || lessonRepository.overlapsParticipant(teacherId, activeLessons, startAt, endAt));
                boolean studentBusy = studentId != null
                        && (bookingRepository.overlapsStudent(studentId, active, startAt, endAt)
                            || lessonRepository.overlapsParticipant(studentId, activeLessons, startAt, endAt));
                if (teacherBusy || studentBusy) {
                    conflicted.add(localDate + " (конфликт)");
                    continue;
                }

                Booking booking = new Booking();
                booking.setCourse(course);
                booking.setStudent(student);
                booking.setTeacher(tutor);
                booking.setStartAt(startAt);
                booking.setEndAt(endAt);
                booking.setDurationMinutes(duration);
                booking.setStatus(Booking.Status.CONFIRMED);
                booking.setEnrollment(app);
                booking.setSchedule(schedule);

                Lesson lesson = new Lesson();
                lesson.setCourse(course);
                lesson.setTeacher(tutor);
                lesson.setStudent(student);
                lesson.setTitle(course != null ? course.getTitle() : "Tutoring session");
                lesson.setStartAt(startAt);
                lesson.setEndAt(endAt);
                lesson.setStatus(Lesson.Status.SCHEDULED);
                lesson.setSchedule(schedule);
                lesson.setBooking(booking);
                lesson.setLocationType(schedule.getLocationType());
                lesson.setLocationAddress(schedule.getLocationAddress());
                lesson.setLocationDetails(schedule.getLocationDetails());

                bookings.add(booking);
                lessons.add(lesson);
                createdStatuses.add(Booking.Status.CONFIRMED);
            }
            day = day.plusDays(1);
        }

        if (!bookings.isEmpty()) {
            List<Booking> savedBookings = bookingRepository.saveAll(bookings);
            lessonRepository.saveAll(lessons);
            auditLogService.logSync(AuditEntry.of(app.getStudent() != null ? app.getStudent().getId() : null,
                    "LESSONS_GENERATED", "APPLICATION", app.getId())
                    .withDetails("created=" + savedBookings.size()));
            return new Generated(savedBookings.size(), conflicted,
                    savedBookings.stream().map(Booking::getId).toList());
        }
        auditLogService.logSync(AuditEntry.of(app.getStudent() != null ? app.getStudent().getId() : null,
                "LESSONS_GENERATED", "APPLICATION", app.getId())
                .withDetails("created=0 conflicted=" + conflicted));
        return new Generated(0, conflicted, List.of());
    }

    // ---------------- helpers ----------------

    private List<UUID> existingBookings(Schedule schedule) {
        return bookingRepository.findByScheduleIdOrderByStartAtAsc(schedule.getId()).stream()
                .map(Booking::getId).toList();
    }

private AcceptResponse toAcceptResponse(Schedule schedule, int created, List<String> conflicted,
                                        List<UUID> bookings) {
    return new AcceptResponse(toScheduleResponse(schedule, null), created, conflicted, bookings);
}

private ScheduleResponse toScheduleResponse(Schedule s, UUID viewerId) {
        long bookedCount = bookingRepository.findByScheduleIdAndStatusIn(s.getId(),
                List.of(Booking.Status.CONFIRMED, Booking.Status.COMPLETED, Booking.Status.RESCHEDULED)).size();
        return new ScheduleResponse(
                s.getId(),
                s.getApplication() != null ? s.getApplication().getId() : null,
                s.getCourse() != null ? s.getCourse().getId() : null,
                s.getCourse() != null ? s.getCourse().getTitle() : null,
                s.getStudentId(),
                s.getStudent() != null ? s.getStudent().getFullName() : null,
                s.getTutorId(),
                s.getTutor() != null ? s.getTutor().getFullName() : null,
                s.getFormat().name(),
                s.getLocationType() != null ? s.getLocationType().name() : null,
                s.getLocationAddress(),
                s.getLocationDetails(),
                DateTimeFormatter.ISO_LOCAL_DATE.format(s.getStartDate()),
                DateTimeFormatter.ISO_LOCAL_DATE.format(s.getEndDate()),
                s.getTimezone(),
                s.getFrequency().name(),
                s.getDurationMinutes(),
                s.getStatus().name(),
                s.getSlots().stream().map(x -> new SlotResponse(
                        x.getWeekday().name(), x.getStartTime().toString(), x.getEndTime().toString())).toList(),
                (int) bookedCount,
                null,
                s.getCreatedAt(),
                s.getUpdatedAt());
    }

    private ScheduleProposalResponse toProposalResponse(ScheduleProposal p) {
        User creator = p.getCreatedBy();
        return new ScheduleProposalResponse(
                p.getId(),
                p.getSchedule() != null ? p.getSchedule().getId() : null,
                p.getApplication() != null ? p.getApplication().getId() : null,
                creator != null ? creator.getId() : null,
                creator != null ? creator.getFullName() : null,
                creator != null && creator.getRole() != null ? creator.getRole().name() : null,
                p.getStatus().name(),
                p.getTimezone(),
                DateTimeFormatter.ISO_LOCAL_DATE.format(p.getStartDate()),
                DateTimeFormatter.ISO_LOCAL_DATE.format(p.getEndDate()),
                p.getDurationMinutes(),
                p.getMessage(),
                p.getSlots().stream().map(x -> new SlotResponse(
                        x.getWeekday().name(), x.getStartTime().toString(), x.getEndTime().toString())).toList(),
                p.getCreatedAt());
    }

    private LocalTime effectiveStart(Enrollment app, AvailabilitySlot slot) {
        LocalTime start = app.getPreferredStartTime();
        if (start != null && start.isAfter(slot.getStartTime())) {
            return start;
        }
        return slot.getStartTime();
    }

    private LocalTime effectiveEnd(Enrollment app, AvailabilitySlot slot) {
        LocalTime end = app.getPreferredEndTime();
        if (end != null && end.isBefore(slot.getEndTime())) {
            return end;
        }
        return slot.getEndTime();
    }

    private ZoneId dayZone(Enrollment app, String timezone) {
        return ScheduleParser.parseZone(timezone);
    }

    private record ParsedProposal(ZoneId zone, LocalDate startDate, LocalDate endDate,
                                  int duration, Schedule.Format format,
                                  LocationType locationType, String locationAddress, String locationDetails) {}

    private ParsedProposal parse(ProposeRequest req) {
        if (req == null || req.slots() == null || req.slots().isEmpty()) {
            throw ApiException.validation("slots is required (at least one weekday with time range)");
        }
        ZoneId zone = ScheduleParser.parseZone(req.timezone());
        LocalDate startDate = ScheduleParser.parseDate(req.start_date());
        LocalDate endDate = ScheduleParser.parseDate(req.end_date());
        if (endDate.isBefore(startDate)) {
            throw ApiException.validation(ErrorCodes.INVALID_DATE, "end_date must be on or after start_date");
        }
        if (startDate.isBefore(LocalDate.now())) {
            throw ApiException.validation(ErrorCodes.INVALID_DATE, "start_date must not be in the past");
        }
        int duration = req.duration_minutes() == null ? 60 : req.duration_minutes();
        ScheduleParser.requireDuration(duration);
        Schedule.Format format;
        if (req.format() == null || req.format().isBlank() || req.format().equalsIgnoreCase("ONLINE")) {
            format = Schedule.Format.ONLINE;
        } else if (req.format().equalsIgnoreCase("OFFLINE")) {
            format = Schedule.Format.OFFLINE;
        } else {
            throw ApiException.validation("format must be ONLINE or OFFLINE");
        }
        LocationType locationType = null;
        if (req.location_type() != null && !req.location_type().isBlank()) {
            try {
                locationType = LocationType.valueOf(req.location_type().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ApiException.validation("location_type must be one of TUTOR_PLACE, STUDENT_PLACE, CENTER, OTHER");
            }
        }
        if (format == Schedule.Format.OFFLINE && locationType == null) {
            throw ApiException.validation(ErrorCodes.SCHEDULE_NOT_AVAILABLE,
                    "location_type is required for OFFLINE schedule");
        }
        for (SlotRequest slot : req.slots()) {
            LocalTime startTime = ScheduleParser.parseTime(slot.start_time());
            LocalTime endTime = ScheduleParser.parseTime(slot.end_time());
            if (!endTime.isAfter(startTime)) {
                throw ApiException.validation("end_time must be after start_time for slot on "
                        + slot.weekday());
            }
            int slotMinutes = (int) ((endTime.toSecondOfDay() - startTime.toSecondOfDay()) / 60L);
            if (slotMinutes < duration) {
                throw ApiException.validation("slot time range must be at least duration_minutes");
            }
        }
        return new ParsedProposal(zone, startDate, endDate, duration, format,
                locationType, req.location_address(), req.location_details());
    }

    private static DayOfWeek parseWeekday(String weekday) {
        if (weekday == null || weekday.isBlank()) {
            throw ApiException.validation("weekday is required");
        }
        String value = weekday.trim().toUpperCase(Locale.ROOT);
        if (value.matches("\\d{1,2}")) {
            try {
                return DayOfWeek.of(Integer.parseInt(value));
            } catch (java.time.DateTimeException e) {
                throw ApiException.validation("Invalid weekday: " + weekday);
            }
        }
        try {
            return DayOfWeek.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("Invalid weekday: " + weekday);
        }
    }

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

    private Schedule buildSchedule(Enrollment app, ParsedProposal parsed) {
        Schedule schedule = new Schedule();
        schedule.setApplication(app);
        schedule.setCourse(app.getCourse());
        schedule.setStudent(app.getStudent());
        schedule.setTutor(app.getTutor() != null ? app.getTutor() : tutorOf(app));
        schedule.setFrequency(Schedule.Frequency.WEEKLY);
        return schedule;
    }

    private Enrollment requireApplication(UUID id) {
        return enrollmentRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.APPLICATION_NOT_FOUND, "Application not found"));
    }

    private Schedule requireSchedule(UUID id) {
        return scheduleRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Schedule not found"));
    }

    private ScheduleProposal requireProposal(UUID id) {
        return proposalRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Schedule proposal not found"));
    }

    private Enrollment requireApplicationParticipant(UUID applicationId, User viewer) {
        Enrollment app = requireApplication(applicationId);
        if (!participant(app, viewer)) {
            throw ApiException.forbidden(ErrorCodes.NOT_APPLICATION_OWNER, "You have no access to this application");
        }
        return app;
    }

    private void requireApplicationOwner(Enrollment app, UUID studentId) {
        if (app.getStudent() == null || !studentId.equals(app.getStudent().getId())) {
            throw ApiException.forbidden(ErrorCodes.NOT_APPLICATION_OWNER, "Only the student can confirm the schedule");
        }
    }

    private void requireCourseTutor(Enrollment app, UUID userId) {
        User tutor = tutorOf(app);
        if (tutor == null || !userId.equals(tutor.getId())) {
            throw ApiException.forbidden(ErrorCodes.NOT_COURSE_OWNER, "Only the course tutor can propose a schedule");
        }
    }

    private void requireScheduleParticipant(Schedule schedule, User viewer) {
        boolean admin = viewer != null && (viewer.getRole() == com.okututor.backend.user.Role.ADMIN
                || viewer.getRole() == com.okututor.backend.user.Role.SUPER_ADMIN);
        if (viewer == null || (!schedule.involves(viewer.getId()) && !admin)) {
            throw ApiException.forbidden(ErrorCodes.NOT_APPLICATION_OWNER, "You have no access to this schedule");
        }
    }

    private static boolean participant(Enrollment app, User viewer) {
        if (viewer == null) {
            return false;
        }
        if (app.getStudent() != null && viewer.getId().equals(app.getStudent().getId())) {
            return true;
        }
        User tutor = tutorOf(app);
        return tutor != null && viewer.getId().equals(tutor.getId());
    }

    private static User tutorOf(Enrollment app) {
        if (app.getTutor() != null) {
            return app.getTutor();
        }
        Course course = app.getCourse();
        return course != null ? course.getTeacher() : null;
    }

    private static String courseTitle(Enrollment app) {
        return app.getCourse() != null ? app.getCourse().getTitle() : "Курс";
    }
}