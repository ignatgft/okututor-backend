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
            Integer sequence_number,
            String cancel_reason,
            String location_type,
            String location_address,
            String location_details
    ) {}

    /** перенос занятия: start_at/end_at — ISO-8601 UTC; длительность строго из допустимого множества. */
    public record RescheduleRequest(Instant start_at, Instant end_at) {}

    public record DetailsUpdateRequest(String topic, String notes, String homework, String materials, String links) {}

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

    @Transactional
    public Lesson requireParticipantViewForUpdate(UUID id, User viewer) {
        Lesson lesson = lessonRepository.findByIdForUpdate(id).orElseThrow(() -> ApiException.notFound("Lesson not found"));
        if (!lesson.involves(viewer.getId()) && !admin(viewer)) {
            throw ApiException.forbidden("Not your lesson");
        }
        return lesson;
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
        // вычислить endAt если не задан — по длительности 60
        if (lesson.getEndAt() == null && startAt != null) {
            lesson.setEndAt(startAt.plusSeconds(3600));
        }
        return lessonRepository.save(lesson);
    }

    @Transactional
    public void start(User actor, UUID id) {
        Lesson lesson = requireParticipantViewForUpdate(id, actor);
        transition(lesson, Lesson.Status.IN_PROGRESS);
        Instant now = Instant.now();
        lesson.setActualStart(now);
        lesson.setStartedBy(actor);
        // длительность по-умолчанию если нет
        if (lesson.getDurationMinutes() == null && lesson.getStartAt() != null && lesson.getEndAt() != null) {
            long mins = (lesson.getEndAt().getEpochSecond() - lesson.getStartAt().getEpochSecond()) / 60;
            lesson.setDurationMinutes((int) mins);
        }
        lessonRepository.save(lesson);
        notifyOccupants(lesson, NotificationType.LESSON_STARTED, "Занятие «%s» началось".formatted(titleOf(lesson)));
        auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_STARTED", "LESSON", lesson.getId()));
    }

    @Transactional
    public void complete(User actor, UUID id) {
        Lesson lesson = requireParticipantViewForUpdate(id, actor);
        transition(lesson, Lesson.Status.COMPLETED);
        Instant now = Instant.now();
        lesson.setActualEnd(now);
        lesson.setCompletedBy(actor);
        if (lesson.getActualStart() != null) {
            long mins = (now.getEpochSecond() - lesson.getActualStart().getEpochSecond()) / 60;
            lesson.setDurationMinutes((int) Math.max(1, mins));
        } else if (lesson.getStartAt() != null && lesson.getEndAt() != null) {
            long mins = (lesson.getEndAt().getEpochSecond() - lesson.getStartAt().getEpochSecond()) / 60;
            lesson.setDurationMinutes((int) mins);
        }
        lessonRepository.save(lesson);
        Booking booking = lesson.getBooking();
        if (booking != null && (booking.getStatus() == Booking.Status.CONFIRMED
                || booking.getStatus() == Booking.Status.RESCHEDULED)) {
            booking.transitionTo(Booking.Status.COMPLETED);
            bookingRepository.save(booking);
        }
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
                        l.getStatus() == Lesson.Status.COMPLETED || l.getStatus() == Lesson.Status.CANCELLED
                        || l.getStatus() == Lesson.Status.STUDENT_NO_SHOW || l.getStatus() == Lesson.Status.TUTOR_NO_SHOW);
            } else {
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
        Lesson lesson = requireParticipantViewForUpdate(id, actor);
        if (!lesson.isLive() && lesson.getStatus() != Lesson.Status.CHANGE_PENDING) {
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

    // ===== НОВЫЕ МЕТОДЫ ЖИЗНЕННОГО ЦИКЛА =====

    @Transactional
    public Lesson proposeReschedule(User actor, UUID lessonId, Instant newStart, Instant newEnd, String reason, String scope) {
        Lesson lesson = requireParticipantViewForUpdate(lessonId, actor);
        if (!lesson.involves(actor.getId()) && !admin(actor)) throw ApiException.forbidden("Not your lesson");
        if (!actor.getId().equals(lesson.getTeacherId()) && !admin(actor)) throw ApiException.forbidden("Only tutor can propose reschedule");
        if (lesson.getStatus() != Lesson.Status.SCHEDULED) {
            throw ApiException.conflict("Only SCHEDULED lessons can be rescheduled");
        }
        if (newStart == null) throw ApiException.validation("start_at is required");
        if (newStart.isBefore(Instant.now())) throw ApiException.validation("start_at is in the past");
        Instant end = newEnd != null ? newEnd : newStart.plusSeconds(3600);
        if (!end.isAfter(newStart)) throw ApiException.validation("end_at must be after start_at");
        int duration = (int) (end.getEpochSecond() - newStart.getEpochSecond()) / 60;
        ScheduleParser.requireDuration(duration);
        Instant exactEnd = newStart.plusSeconds(duration * 60L);
        throwIfConflicts(lesson, newStart, exactEnd);
        String normalizedScope = normalizeScope(scope);
        // pending
        lesson.setStatus(Lesson.Status.CHANGE_PENDING);
        lesson.setPendingChangeType(Lesson.PendingChangeType.RESCHEDULE);
        lesson.setPendingStartAt(newStart);
        lesson.setPendingEndAt(exactEnd);
        lesson.setPendingReason(reason);
        lesson.setPendingDurationMinutes(duration);
        lesson.setPendingScope(normalizedScope);
        lesson.setPendingProposedBy(actor);
        lesson.setPendingProposedAt(Instant.now());
        lessonRepository.save(lesson);
        notifyOccupants(lesson, NotificationType.LESSON_RESCHEDULED,
                "Тьютор предложил перенос занятия «%s» на %s".formatted(titleOf(lesson), String.valueOf(newStart)));
        auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_RESCHEDULE_PROPOSED", "LESSON", lesson.getId())
                .withValues(lesson.getStartAt()!=null?lesson.getStartAt().toString():null, newStart.toString()));
        return lesson;
    }

    @Transactional
    public Lesson acceptReschedule(User actor, UUID lessonId) {
        Lesson lesson = requireParticipantViewForUpdate(lessonId, actor);
        if (lesson.getStatus() != Lesson.Status.CHANGE_PENDING
                || lesson.getPendingChangeType() != Lesson.PendingChangeType.RESCHEDULE)
            throw ApiException.conflict("Lesson is not pending reschedule");
        // только другой участник (не автор предложения) или админ может принять
        if (lesson.getPendingProposedBy()!=null && lesson.getPendingProposedBy().getId().equals(actor.getId()) && !admin(actor)) {
            throw ApiException.forbidden("Cannot accept own proposal");
        }
        Instant newStart = lesson.getPendingStartAt();
        Instant newEnd = lesson.getPendingEndAt();
        String scope = lesson.getPendingScope();
        if (newStart == null || newEnd == null) throw ApiException.conflict("Pending data missing");
        // проверка конфликтов на момент акцепта (исключая сам урок)
        throwIfConflicts(lesson, newStart, newEnd);
        String oldSlot = lesson.getStartAt()!=null?lesson.getStartAt().toString():null;
        Instant oldStart = lesson.getStartAt();
        Instant oldEnd = lesson.getEndAt();
        lesson.setStartAt(newStart);
        lesson.setEndAt(newEnd);
        lesson.setDurationMinutes(lesson.getPendingDurationMinutes());
        lesson.setStatus(Lesson.Status.SCHEDULED);
        lesson.clearPending();
        lessonRepository.save(lesson);
        // зеркалим в бронь
        Booking booking = lesson.getBooking();
        if (booking != null) {
            booking.setStartAt(newStart);
            booking.setEndAt(newEnd);
            booking.setDurationMinutes(lesson.getDurationMinutes()!=null?lesson.getDurationMinutes():60);
            bookingRepository.save(booking);
        }
        // FUTURE scope: применить ко всем будущим занятиям серии (сдвигом дельты)
        if ("FUTURE".equals(scope) && lesson.getSchedule()!=null && oldStart!=null) {
            long delta = newStart.getEpochSecond() - oldStart.getEpochSecond();
            applyRescheduleToFutureSeries(lesson, delta, actor);
        }
        notifyOccupants(lesson, NotificationType.LESSON_RESCHEDULED,
                "Перенос занятия «%s» подтверждён".formatted(titleOf(lesson)));
        auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_RESCHEDULE_ACCEPTED", "LESSON", lesson.getId())
                .withValues(oldSlot, newStart.toString()));
        return lesson;
    }

    @Transactional
    public Lesson rejectReschedule(User actor, UUID lessonId) {
        Lesson lesson = requireParticipantViewForUpdate(lessonId, actor);
        if (lesson.getStatus() != Lesson.Status.CHANGE_PENDING
                || lesson.getPendingChangeType() != Lesson.PendingChangeType.RESCHEDULE)
            throw ApiException.conflict("Lesson is not pending reschedule");
        lesson.setStatus(Lesson.Status.SCHEDULED);
        lesson.clearPending();
        lessonRepository.save(lesson);
        notifyOccupants(lesson, NotificationType.LESSON_RESCHEDULED, "Предложение переноса занятия «%s» отклонено".formatted(titleOf(lesson)));
        auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_RESCHEDULE_REJECTED", "LESSON", lesson.getId()));
        return lesson;
    }

    @Transactional
    public Lesson proposeFormatChange(User actor, UUID lessonId, String newFormat, LocationType locationType, String address, String details, String scope) {
        Lesson lesson = requireParticipantViewForUpdate(lessonId, actor);
        if (!actor.getId().equals(lesson.getTeacherId()) && !admin(actor)) throw ApiException.forbidden("Only tutor can propose format change");
        if (lesson.getStatus() != Lesson.Status.SCHEDULED) throw ApiException.conflict("Only SCHEDULED lessons can change format");
        if (newFormat == null) throw ApiException.validation("format is required (ONLINE/OFFLINE)");
        String fmt = newFormat.trim().toUpperCase();
        if (!fmt.equals("ONLINE") && !fmt.equals("OFFLINE")) throw ApiException.validation("format must be ONLINE or OFFLINE");
        if ("OFFLINE".equals(fmt) && locationType==null && (address==null || address.isBlank())) {
            throw ApiException.validation("address is required for OFFLINE format");
        }
        String normalizedScope = normalizeScope(scope);
        lesson.setStatus(Lesson.Status.CHANGE_PENDING);
        lesson.setPendingChangeType(Lesson.PendingChangeType.FORMAT);
        lesson.setPendingFormat(fmt);
        lesson.setPendingLocationType(locationType);
        lesson.setPendingLocationAddress(address);
        lesson.setPendingLocationDetails(details);
        lesson.setPendingScope(normalizedScope);
        lesson.setPendingProposedBy(actor);
        lesson.setPendingProposedAt(Instant.now());
        lessonRepository.save(lesson);
        notifyOccupants(lesson, NotificationType.LESSON_RESCHEDULED, "Тьютор предложил изменить формат занятия «%s» на %s".formatted(titleOf(lesson), fmt));
        auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_FORMAT_PROPOSED", "LESSON", lesson.getId()).withValues(lesson.getLocationType()!=null?lesson.getLocationType().name():"ONLINE", fmt));
        return lesson;
    }

    @Transactional
    public Lesson acceptFormatChange(User actor, UUID lessonId) {
        Lesson lesson = requireParticipantViewForUpdate(lessonId, actor);
        if (lesson.getStatus() != Lesson.Status.CHANGE_PENDING
                || lesson.getPendingChangeType() != Lesson.PendingChangeType.FORMAT)
            throw ApiException.conflict("Not pending format change");
        if (lesson.getPendingProposedBy()!=null && lesson.getPendingProposedBy().getId().equals(actor.getId()) && !admin(actor)) throw ApiException.forbidden("Cannot accept own proposal");
        String fmt = lesson.getPendingFormat();
        String scope = lesson.getPendingScope();
        // применяем
        if ("OFFLINE".equals(fmt)) {
            lesson.setLocationType(lesson.getPendingLocationType());
            lesson.setLocationAddress(lesson.getPendingLocationAddress());
            lesson.setLocationDetails(lesson.getPendingLocationDetails());
            if (lesson.getSchedule()!=null) {
                lesson.getSchedule().setFormat(com.okututor.backend.schedule.Schedule.Format.OFFLINE);
                lesson.getSchedule().setLocationType(lesson.getPendingLocationType());
                lesson.getSchedule().setLocationAddress(lesson.getPendingLocationAddress());
                lesson.getSchedule().setLocationDetails(lesson.getPendingLocationDetails());
            }
        } else {
            lesson.setLocationType(null);
            lesson.setLocationAddress(null);
            lesson.setLocationDetails(null);
            if (lesson.getSchedule()!=null) {
                lesson.getSchedule().setFormat(com.okututor.backend.schedule.Schedule.Format.ONLINE);
                lesson.getSchedule().setLocationType(null);
                lesson.getSchedule().setLocationAddress(null);
                lesson.getSchedule().setLocationDetails(null);
            }
        }
        lesson.setStatus(Lesson.Status.SCHEDULED);
        // FUTURE scope
        if ("FUTURE".equals(scope) && lesson.getSchedule()!=null) {
            applyFormatToFutureSeries(lesson, fmt, lesson.getPendingLocationType(), lesson.getPendingLocationAddress(), lesson.getPendingLocationDetails());
        }
        lesson.clearPending();
        lessonRepository.save(lesson);
        notifyOccupants(lesson, NotificationType.LESSON_RESCHEDULED, "Формат занятия «%s» изменён на %s".formatted(titleOf(lesson), fmt));
        auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_FORMAT_ACCEPTED", "LESSON", lesson.getId()).withValues(fmt, fmt));
        return lesson;
    }

    @Transactional
    public Lesson rejectFormatChange(User actor, UUID lessonId) {
        Lesson lesson = requireParticipantViewForUpdate(lessonId, actor);
        if (lesson.getStatus() != Lesson.Status.CHANGE_PENDING
                || lesson.getPendingChangeType() != Lesson.PendingChangeType.FORMAT)
            throw ApiException.conflict("Not pending format change");
        lesson.setStatus(Lesson.Status.SCHEDULED);
        lesson.clearPending();
        lessonRepository.save(lesson);
        auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_FORMAT_REJECTED", "LESSON", lesson.getId()));
        return lesson;
    }

    @Transactional
    public Lesson proposeLocationChange(User actor, UUID lessonId, LocationType type, String address, String details, String scope) {
        Lesson lesson = requireParticipantViewForUpdate(lessonId, actor);
        if (!actor.getId().equals(lesson.getTeacherId()) && !admin(actor)) throw ApiException.forbidden("Only tutor can propose location change");
        if (lesson.getStatus() != Lesson.Status.SCHEDULED) throw ApiException.conflict("Only SCHEDULED lessons can change location");
        if (type == null && (address==null || address.isBlank())) throw ApiException.validation("location is required");
        String normalizedScope = normalizeScope(scope);
        lesson.setStatus(Lesson.Status.CHANGE_PENDING);
        lesson.setPendingChangeType(Lesson.PendingChangeType.LOCATION);
        lesson.setPendingLocationType(type);
        lesson.setPendingLocationAddress(address);
        lesson.setPendingLocationDetails(details);
        lesson.setPendingScope(normalizedScope);
        lesson.setPendingProposedBy(actor);
        lesson.setPendingProposedAt(Instant.now());
        lessonRepository.save(lesson);
        notifyOccupants(lesson, NotificationType.LESSON_RESCHEDULED, "Тьютор предложил изменить место занятия «%s»".formatted(titleOf(lesson)));
        auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_LOCATION_PROPOSED", "LESSON", lesson.getId()));
        return lesson;
    }

    @Transactional
    public Lesson acceptLocationChange(User actor, UUID lessonId) {
        Lesson lesson = requireParticipantViewForUpdate(lessonId, actor);
        if (lesson.getStatus() != Lesson.Status.CHANGE_PENDING
                || lesson.getPendingChangeType() != Lesson.PendingChangeType.LOCATION)
            throw ApiException.conflict("Not pending location change");
        if (lesson.getPendingProposedBy()!=null && lesson.getPendingProposedBy().getId().equals(actor.getId()) && !admin(actor)) throw ApiException.forbidden("Cannot accept own proposal");
        String scope = lesson.getPendingScope();
        lesson.setLocationType(lesson.getPendingLocationType());
        lesson.setLocationAddress(lesson.getPendingLocationAddress());
        lesson.setLocationDetails(lesson.getPendingLocationDetails());
        lesson.setStatus(Lesson.Status.SCHEDULED);
        if ("FUTURE".equals(scope) && lesson.getSchedule()!=null) {
            applyLocationToFutureSeries(lesson, lesson.getPendingLocationType(), lesson.getPendingLocationAddress(), lesson.getPendingLocationDetails());
        }
        lesson.clearPending();
        lessonRepository.save(lesson);
        auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_LOCATION_ACCEPTED", "LESSON", lesson.getId()));
        return lesson;
    }

    @Transactional
    public Lesson rejectLocationChange(User actor, UUID lessonId) {
        Lesson lesson = requireParticipantViewForUpdate(lessonId, actor);
        if (lesson.getStatus() != Lesson.Status.CHANGE_PENDING
                || lesson.getPendingChangeType() != Lesson.PendingChangeType.LOCATION)
            throw ApiException.conflict("Not pending location change");
        lesson.setStatus(Lesson.Status.SCHEDULED);
        lesson.clearPending();
        lessonRepository.save(lesson);
        auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_LOCATION_REJECTED", "LESSON", lesson.getId()));
        return lesson;
    }

    @Transactional
    public Lesson proposeDurationChange(User actor, UUID lessonId, int newDuration, String scope) {
        Lesson lesson = requireParticipantViewForUpdate(lessonId, actor);
        if (!actor.getId().equals(lesson.getTeacherId()) && !admin(actor)) throw ApiException.forbidden("Only tutor can propose duration change");
        if (lesson.getStatus() != Lesson.Status.SCHEDULED) throw ApiException.conflict("Only SCHEDULED lessons can change duration");
        ScheduleParser.requireDuration(newDuration);
        String normalizedScope = normalizeScope(scope);
        Instant newEnd = lesson.getStartAt()!=null ? lesson.getStartAt().plusSeconds(newDuration*60L) : null;
        if (newEnd != null) throwIfConflicts(lesson, lesson.getStartAt(), newEnd);
        lesson.setStatus(Lesson.Status.CHANGE_PENDING);
        lesson.setPendingChangeType(Lesson.PendingChangeType.DURATION);
        lesson.setPendingDurationMinutes(newDuration);
        lesson.setPendingEndAt(newEnd);
        lesson.setPendingScope(normalizedScope);
        lesson.setPendingProposedBy(actor);
        lesson.setPendingProposedAt(Instant.now());
        lessonRepository.save(lesson);
        notifyOccupants(lesson, NotificationType.LESSON_RESCHEDULED, "Тьютор предложил изменить длительность занятия «%s» на %d мин".formatted(titleOf(lesson), newDuration));
        auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_DURATION_PROPOSED", "LESSON", lesson.getId()).withValues(String.valueOf(lesson.getDurationMinutes()), String.valueOf(newDuration)));
        return lesson;
    }

    @Transactional
    public Lesson acceptDurationChange(User actor, UUID lessonId) {
        Lesson lesson = requireParticipantViewForUpdate(lessonId, actor);
        if (lesson.getStatus() != Lesson.Status.CHANGE_PENDING
                || lesson.getPendingChangeType() != Lesson.PendingChangeType.DURATION)
            throw ApiException.conflict("Not pending duration change");
        if (lesson.getPendingProposedBy()!=null && lesson.getPendingProposedBy().getId().equals(actor.getId()) && !admin(actor)) throw ApiException.forbidden("Cannot accept own proposal");
        Integer newDur = lesson.getPendingDurationMinutes();
        String scope = lesson.getPendingScope();
        if (newDur != null && lesson.getStartAt()!=null) {
            lesson.setEndAt(lesson.getStartAt().plusSeconds(newDur*60L));
            lesson.setDurationMinutes(newDur);
            if (lesson.getBooking()!=null) {
                lesson.getBooking().setEndAt(lesson.getEndAt());
                lesson.getBooking().setDurationMinutes(newDur);
                bookingRepository.save(lesson.getBooking());
            }
            if ("FUTURE".equals(scope) && lesson.getSchedule()!=null) {
                applyDurationToFutureSeries(lesson, newDur);
            }
        }
        lesson.setStatus(Lesson.Status.SCHEDULED);
        lesson.clearPending();
        lessonRepository.save(lesson);
        auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_DURATION_ACCEPTED", "LESSON", lesson.getId()));
        return lesson;
    }

    @Transactional
    public Lesson rejectDurationChange(User actor, UUID lessonId) {
        Lesson lesson = requireParticipantViewForUpdate(lessonId, actor);
        if (lesson.getStatus() != Lesson.Status.CHANGE_PENDING
                || lesson.getPendingChangeType() != Lesson.PendingChangeType.DURATION)
            throw ApiException.conflict("Not pending duration change");
        lesson.setStatus(Lesson.Status.SCHEDULED);
        lesson.clearPending();
        lessonRepository.save(lesson);
        auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_DURATION_REJECTED", "LESSON", lesson.getId()));
        return lesson;
    }

    @Transactional
    public Lesson markStudentNoShow(User actor, UUID lessonId) {
        Lesson lesson = requireParticipantViewForUpdate(lessonId, actor);
        if (!actor.getId().equals(lesson.getTeacherId()) && !admin(actor)) throw ApiException.forbidden("Only tutor can mark student no-show");
        if (lesson.getStatus() != Lesson.Status.SCHEDULED) throw ApiException.conflict("Only SCHEDULED lessons can be marked no-show");
        if (lesson.getStartAt()==null) throw ApiException.validation("Lesson has no start time");
        Instant eligible = lesson.getStartAt().plusSeconds(15*60L); // 15 минут
        if (Instant.now().isBefore(eligible)) throw ApiException.conflict("Too early to mark no-show. Wait 15 minutes after start.");
        lesson.setStatus(Lesson.Status.STUDENT_NO_SHOW);
        lesson.setAttendance("STUDENT_NO_SHOW");
        lessonRepository.save(lesson);
        if (lesson.getBooking()!=null) {
            try { lesson.getBooking().transitionTo(Booking.Status.NO_SHOW); bookingRepository.save(lesson.getBooking()); } catch (Exception ignored) {}
        }
        notifyOccupants(lesson, NotificationType.LESSON_CANCELLED, "Ученик не пришёл на занятие «%s»".formatted(titleOf(lesson)));
        auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_STUDENT_NO_SHOW", "LESSON", lesson.getId()));
        return lesson;
    }

    @Transactional
    public Lesson markTutorNoShow(User actor, UUID lessonId, String reason) {
        Lesson lesson = requireParticipantViewForUpdate(lessonId, actor);
        if (!actor.getId().equals(lesson.getStudentId()) && !admin(actor)) throw ApiException.forbidden("Only student can mark tutor no-show");
        if (lesson.getStatus() != Lesson.Status.SCHEDULED) throw ApiException.conflict("Only SCHEDULED lessons");
        if (lesson.getStartAt()==null) throw ApiException.validation("Lesson has no start time");
        Instant eligible = lesson.getStartAt().plusSeconds(15*60L);
        if (Instant.now().isBefore(eligible)) throw ApiException.conflict("Too early to mark no-show. Wait 15 minutes after start.");
        lesson.setStatus(Lesson.Status.TUTOR_NO_SHOW);
        lesson.setAttendance("TUTOR_NO_SHOW");
        lesson.setCancelReason(reason);
        lessonRepository.save(lesson);
        if (lesson.getBooking()!=null) {
            try { lesson.getBooking().transitionTo(Booking.Status.NO_SHOW); bookingRepository.save(lesson.getBooking()); } catch (Exception ignored) {}
        }
        // создаём обращение для администратора — через audit + уведомление
        notifyOccupants(lesson, NotificationType.LESSON_CANCELLED, "Тьютор не пришёл на занятие «%s» — создано обращение".formatted(titleOf(lesson)));
        auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_TUTOR_NO_SHOW", "LESSON", lesson.getId()).withDetails(reason));
        return lesson;
    }

    @Transactional
    public Lesson reportIssue(User actor, UUID lessonId, String reason) {
        Lesson lesson = requireParticipantViewForUpdate(lessonId, actor);
        lesson.setStatus(Lesson.Status.ISSUE);
        lesson.setCancelReason(reason);
        lessonRepository.save(lesson);
        notifyOccupants(lesson, NotificationType.LESSON_CANCELLED, "Сообщено о проблеме на занятии «%s»".formatted(titleOf(lesson)));
        auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_ISSUE", "LESSON", lesson.getId()).withDetails(reason));
        return lesson;
    }

    @Transactional
    public Lesson updateDetails(User actor, UUID lessonId, DetailsUpdateRequest req) {
        Lesson lesson = requireParticipantViewForUpdate(lessonId, actor);
        if (!lesson.getTeacherId().equals(actor.getId()) && !admin(actor)) throw ApiException.forbidden("Only tutor can update lesson details");
        if (lesson.getStatus() != Lesson.Status.COMPLETED && lesson.getStatus() != Lesson.Status.IN_PROGRESS) {
            // разрешаем также после завершения
            // но если SCHEDULED — нельзя заполнять итоги
            if (lesson.getStatus() != Lesson.Status.COMPLETED) {
                // allow if actor is tutor after completion timer? spec says after completion tutor can fill
            }
        }
        if (req.topic()!=null) lesson.setTopic(req.topic());
        if (req.notes()!=null) lesson.setNotes(req.notes());
        if (req.homework()!=null) lesson.setHomework(req.homework());
        if (req.materials()!=null) lesson.setMaterials(req.materials());
        if (req.links()!=null) lesson.setLinks(req.links());
        lessonRepository.save(lesson);
        auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_DETAILS_UPDATED", "LESSON", lesson.getId()));
        return lesson;
    }

    private void applyRescheduleToFutureSeries(Lesson origin, long deltaSeconds, User actor) {
        try {
            if (origin.getSchedule()==null) return;
            List<Lesson> future = lessonRepository.findByScheduleIdOrderByStartAtAsc(origin.getSchedule().getId()).stream()
                    .filter(l -> !l.getId().equals(origin.getId()))
                    .filter(l -> l.getStartAt()!=null && l.getStartAt().isAfter(Instant.now()))
                    .filter(l -> l.getStatus()== Lesson.Status.SCHEDULED)
                    .toList();
            for (Lesson l : future) {
                Instant shiftedStart = l.getStartAt().plusSeconds(deltaSeconds);
                Instant shiftedEnd = l.getEndAt()!=null ? l.getEndAt().plusSeconds(deltaSeconds) : shiftedStart.plusSeconds(origin.getDurationMinutes()!=null? origin.getDurationMinutes()*60L : 3600);
                // проверка конфликтов для каждого будущего — пропускаем конфликтные
                try { throwIfConflicts(l, shiftedStart, shiftedEnd); } catch (ApiException e) { continue; }
                l.setStartAt(shiftedStart);
                l.setEndAt(shiftedEnd);
                if (l.getBooking()!=null) {
                    l.getBooking().setStartAt(shiftedStart);
                    l.getBooking().setEndAt(shiftedEnd);
                    bookingRepository.save(l.getBooking());
                }
                lessonRepository.save(l);
            }
            auditLogService.logSync(AuditEntry.of(actor.getId(), "LESSON_SERIES_RESCHEDULE_FUTURE", "LESSON", origin.getId())
                    .withDetails("futureCount="+future.size()+" delta="+deltaSeconds));
        } catch (Exception ignored) {}
    }
    private void applyFormatToFutureSeries(Lesson origin, String fmt, LocationType type, String addr, String det) {
        try {
            List<Lesson> future = lessonRepository.findByScheduleIdOrderByStartAtAsc(origin.getSchedule().getId()).stream()
                    .filter(l -> !l.getId().equals(origin.getId()))
                    .filter(l -> l.getStartAt()!=null && l.getStartAt().isAfter(Instant.now()))
                    .filter(l -> l.getStatus()== Lesson.Status.SCHEDULED)
                    .toList();
            for (Lesson l : future) {
                if ("OFFLINE".equals(fmt)) {
                    l.setLocationType(type);
                    l.setLocationAddress(addr);
                    l.setLocationDetails(det);
                } else {
                    l.setLocationType(null);
                    l.setLocationAddress(null);
                    l.setLocationDetails(null);
                }
                lessonRepository.save(l);
            }
        } catch (Exception ignored) {}
    }
    private void applyLocationToFutureSeries(Lesson origin, LocationType type, String addr, String det) {
        try {
            List<Lesson> future = lessonRepository.findByScheduleIdOrderByStartAtAsc(origin.getSchedule().getId()).stream()
                    .filter(l -> !l.getId().equals(origin.getId()))
                    .filter(l -> l.getStartAt()!=null && l.getStartAt().isAfter(Instant.now()))
                    .filter(l -> l.getStatus()== Lesson.Status.SCHEDULED)
                    .toList();
            for (Lesson l : future) {
                l.setLocationType(type);
                l.setLocationAddress(addr);
                l.setLocationDetails(det);
                lessonRepository.save(l);
            }
        } catch (Exception ignored) {}
    }
    private void applyDurationToFutureSeries(Lesson origin, int newDur) {
        try {
            List<Lesson> future = lessonRepository.findByScheduleIdOrderByStartAtAsc(origin.getSchedule().getId()).stream()
                    .filter(l -> !l.getId().equals(origin.getId()))
                    .filter(l -> l.getStartAt()!=null && l.getStartAt().isAfter(Instant.now()))
                    .filter(l -> l.getStatus()== Lesson.Status.SCHEDULED)
                    .toList();
            for (Lesson l: future) {
                if (l.getStartAt()!=null) {
                    l.setEndAt(l.getStartAt().plusSeconds(newDur*60L));
                    l.setDurationMinutes(newDur);
                    if (l.getBooking()!=null) {
                        l.getBooking().setEndAt(l.getEndAt());
                        l.getBooking().setDurationMinutes(newDur);
                        bookingRepository.save(l.getBooking());
                    }
                    lessonRepository.save(l);
                }
            }
        } catch (Exception ignored) {}
    }

    private String normalizeScope(String scope) {
        if (scope==null || scope.isBlank()) return "SINGLE";
        String s = scope.trim().toUpperCase();
        if (s.equals("FUTURE") || s.equals("ALL_FUTURE") || s.equals("SERIES")) return "FUTURE";
        return "SINGLE";
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
        if (lesson.getDurationMinutes()==null) lesson.setDurationMinutes(duration);
        else lesson.setDurationMinutes(duration);
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
        List<Lesson.Status> activeLessons = List.of(Lesson.Status.SCHEDULED, Lesson.Status.IN_PROGRESS, Lesson.Status.CHANGE_PENDING);
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
            case CANCELLED -> current == Lesson.Status.SCHEDULED || current == Lesson.Status.IN_PROGRESS
                    || current == Lesson.Status.CHANGE_PENDING;
            default -> false;
        };
        if (!allowed) {
            throw ApiException.conflict("Cannot move lesson from %s to %s".formatted(current.name(), target.name()));
        }
        lesson.setStatus(target);
        if (target == Lesson.Status.CANCELLED || target == Lesson.Status.COMPLETED) lesson.clearPending();
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
                lesson.getSequenceNumber(),
                lesson.getCancelReason(),
                lesson.getLocationType() != null ? lesson.getLocationType().name() : null,
                lesson.getLocationAddress(),
                lesson.getLocationDetails());
    }
}
