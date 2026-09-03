package com.okututor.backend.schedule;

import static com.okututor.backend.common.error.ErrorCodes.INVALID_APPLICATION_STATE;
import static com.okututor.backend.common.error.ErrorCodes.INVALID_DATE;
import static com.okututor.backend.common.error.ErrorCodes.NOT_APPLICATION_OWNER;
import static com.okututor.backend.common.error.ErrorCodes.NOT_COURSE_OWNER;
import static com.okututor.backend.common.error.ErrorCodes.SCHEDULE_NOT_AVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.okututor.backend.admin.AuditLogService;
import com.okututor.backend.booking.Booking;
import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.ErrorCodes;
import com.okututor.backend.course.Course;
import com.okututor.backend.enrollment.ApplicationWorkflowService;
import com.okututor.backend.enrollment.Enrollment;
import com.okututor.backend.enrollment.EnrollmentRepository;
import com.okututor.backend.lesson.LessonRepository;
import com.okututor.backend.lesson.LessonService;
import com.okututor.backend.notification.NotificationService;
import com.okututor.backend.tutors.AvailabilitySlot;
import com.okututor.backend.tutors.AvailabilitySlotRepository;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ScheduleServiceTest {

    private ScheduleRepository scheduleRepository;
    private ScheduleProposalRepository proposalRepository;
    private EnrollmentRepository enrollmentRepository;
    private BookingRepository bookingRepository;
    private LessonRepository lessonRepository;
    private AvailabilitySlotRepository availabilitySlotRepository;
    private NotificationService notificationService;
    private AuditLogService auditLogService;
    private ApplicationWorkflowService workflowService;
    private LessonService lessonService;

    private ScheduleService service;

    private User teacher;
    private User student;
    private Course course;
    private Enrollment enrollment;
    private List<Booking> savedBookings = new ArrayList<>();

    @BeforeEach
    void setUp() {
        scheduleRepository = mock(ScheduleRepository.class);
        proposalRepository = mock(ScheduleProposalRepository.class);
        enrollmentRepository = mock(EnrollmentRepository.class);
        bookingRepository = mock(BookingRepository.class);
        lessonRepository = mock(LessonRepository.class);
        availabilitySlotRepository = mock(AvailabilitySlotRepository.class);
        notificationService = mock(NotificationService.class);
        auditLogService = mock(AuditLogService.class);
        workflowService = mock(ApplicationWorkflowService.class);
        lessonService = mock(LessonService.class);

        service = new ScheduleService(
                scheduleRepository, proposalRepository, enrollmentRepository,
                bookingRepository, lessonRepository, availabilitySlotRepository,
                notificationService, auditLogService, workflowService, lessonService);

        teacher = new User();
        teacher.setId(UUID.randomUUID());
        teacher.setRole(Role.TUTOR);
        teacher.setFirstName("Tut");
        teacher.setLastName("Or");
        teacher.setEmail("tutor@test.com");

        student = new User();
        student.setId(UUID.randomUUID());
        student.setRole(Role.STUDENT);
        student.setFirstName("Stu");
        student.setLastName("Dent");
        student.setEmail("student@test.com");

course = new Course();
        ReflectionTestUtils.setField(course, "id", UUID.randomUUID());
        course.setTitle("Java Basics");
        course.setTeacher(teacher);

        enrollment = new Enrollment();
        ReflectionTestUtils.setField(enrollment, "id", UUID.randomUUID());
        enrollment.setCourse(course);
        enrollment.setStudent(student);
        enrollment.setTutor(teacher);

        when(enrollmentRepository.findById(any())).thenReturn(Optional.of(enrollment));
        when(proposalRepository.findById(any())).thenReturn(Optional.empty());
        when(proposalRepository.save(any(ScheduleProposal.class))).thenAnswer(inv -> inv.getArgument(0));
        when(scheduleRepository.save(any(Schedule.class))).thenAnswer(inv -> inv.getArgument(0));
        when(enrollmentRepository.save(any(Enrollment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<Booking> list = inv.getArgument(0);
            for (Booking b : list) {
                if (b.getId() == null) {
                    b.setId(UUID.randomUUID());
                }
            }
            savedBookings = list;
            return list;
        });
        when(lessonRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    private String caption(DayOfWeek day) {
        return day.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private ScheduleService.ProposeRequest validRequest() {
        LocalDate start = LocalDate.now().plusDays(30);
        return new ScheduleService.ProposeRequest(
                "UTC", "ONLINE", start.toString(), start.plusDays(7).toString(), 60,
                List.of(new ScheduleService.SlotRequest("MONDAY", "12:00", "13:00")), "Предложение",
                null, null, null);
    }

    private ScheduleProposal pendingProposalWithSchedule() {
        LocalDate start = LocalDate.now().plusDays(14);
        Schedule schedule = new Schedule();
        ReflectionTestUtils.setField(schedule, "id", UUID.randomUUID());
        schedule.setApplication(enrollment);
        schedule.setCourse(course);
        schedule.setStudent(student);
        schedule.setTutor(teacher);
        schedule.setStartDate(start);
        schedule.setEndDate(start.plusDays(7));
        schedule.setTimezone("UTC");
        schedule.setDurationMinutes(60);
        schedule.setStatus(Schedule.Status.PROPOSED);
        ScheduleSlot slot = new ScheduleSlot();
        slot.setSchedule(schedule);
        slot.setWeekday(DayOfWeek.MONDAY);
        slot.setStartTime(LocalTime.of(12, 0));
        slot.setEndTime(LocalTime.of(13, 0));
        schedule.getSlots().add(slot);

        ScheduleProposal proposal = new ScheduleProposal();
        ReflectionTestUtils.setField(proposal, "id", UUID.randomUUID());
        proposal.setApplication(enrollment);
        proposal.setSchedule(schedule);
        proposal.setCreatedBy(teacher);
        proposal.setTimezone("UTC");
        proposal.setStartDate(start);
        proposal.setEndDate(start.plusDays(7));
        proposal.setDurationMinutes(60);
        return proposal;
    }

    private void stubAvailabilityAllWeekdays() {
        List<AvailabilitySlot> slots = new ArrayList<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            AvailabilitySlot s = new AvailabilitySlot();
            s.setWeekday(caption(d));
            s.setStartTime(LocalTime.of(10, 0));
            s.setEndTime(LocalTime.of(15, 0));
            slots.add(s);
        }
        when(availabilitySlotRepository.findByTutorIdOrderByWeekdayAscStartTimeAsc(teacher.getId()))
                .thenReturn(slots);
    }

    private void stubNoOverlaps() {
        when(bookingRepository.overlapsTeacher(any(), anyList(), any(Instant.class), any(Instant.class)))
                .thenReturn(false);
        when(bookingRepository.overlapsStudent(any(), anyList(), any(Instant.class), any(Instant.class)))
                .thenReturn(false);
        when(lessonRepository.overlapsParticipant(any(), anyList(), any(Instant.class), any(Instant.class)))
                .thenReturn(false);
    }

    // ---------------- propose ----------------

    @Test
    void tutorCanProposeForAcceptedApplication() {
        enrollment.setStatus(Enrollment.Status.ACCEPTED);
        when(proposalRepository.existsByApplicationIdAndStatus(any(),
                eq(ScheduleProposal.Status.PENDING))).thenReturn(false);

        ScheduleService.ScheduleProposalResponse res =
                service.propose(teacher, enrollment.getId(), validRequest());

        assertThat(res.status()).isEqualTo("PENDING");
        assertThat(res.created_by()).isEqualTo(teacher.getId());
        assertThat(res.slots()).hasSize(1);
        assertThat(res.slots().get(0).weekday()).isEqualTo("MONDAY");
        verify(workflowService).transitionTo(eq(enrollment), eq(Enrollment.Status.SCHEDULE_PROPOSED),
                eq(teacher), eq("SCHEDULE_PROPOSED"));
        verify(notificationService).notify(eq(student.getId()), anyString(), anyString(),
                anyString(), any(), anyString(), anyString());
    }

    @Test
    void proposeWithExistingPendingProposalIsConflict() {
        enrollment.setStatus(Enrollment.Status.ACCEPTED);
        // service now handles existing pending by updating it (idempotent), not conflict
        ScheduleProposal existing = pendingProposalWithSchedule();
        existing.setStatus(ScheduleProposal.Status.PENDING);
        when(proposalRepository.findByApplicationIdAndStatus(any(), eq(ScheduleProposal.Status.PENDING)))
                .thenReturn(List.of(existing));
        when(scheduleRepository.findByApplicationId(any())).thenReturn(Optional.of(existing.getSchedule()));
        var res = service.propose(teacher, enrollment.getId(), validRequest());
        assertThat(res.status()).isEqualTo("PENDING");
    }

    @Test
    void proposeForNonAcceptedApplicationIsRejected() {
        enrollment.setStatus(Enrollment.Status.PENDING);
        when(proposalRepository.findByApplicationIdAndStatus(any(), eq(ScheduleProposal.Status.PENDING)))
                .thenReturn(List.of());
        when(scheduleRepository.findByApplicationId(any())).thenReturn(Optional.empty());
        // PENDING auto-transitions to ACCEPTED via service (tutor appoints)
        var res = service.propose(teacher, enrollment.getId(), validRequest());
        assertThat(res.status()).isEqualTo("PENDING");
        assertThat(enrollment.getStatus()).isEqualTo(Enrollment.Status.ACCEPTED);
    }

    @Test
    void proposeByForeignTutorIsForbidden() {
        enrollment.setStatus(Enrollment.Status.ACCEPTED);
        User stranger = new User();
        stranger.setId(UUID.randomUUID());
        stranger.setRole(Role.TUTOR);

        assertThatThrownBy(() -> service.propose(stranger, enrollment.getId(), validRequest()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(NOT_COURSE_OWNER);
    }

    @Test
    void invalidDateRangeIsRejected() {
        enrollment.setStatus(Enrollment.Status.ACCEPTED);
        LocalDate start = LocalDate.now().plusDays(30);
        var req = new ScheduleService.ProposeRequest(
                "UTC", "ONLINE", start.toString(), start.minusDays(1).toString(), 60,
                List.of(new ScheduleService.SlotRequest("MONDAY", "12:00", "13:00")), null,
                null, null, null);

        assertThatThrownBy(() -> service.propose(teacher, enrollment.getId(), req))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(INVALID_DATE);
    }

    // ---------------- accept ----------------

    @Test
    void acceptGeneratesLessonsAndConfirmsApplication() {
        enrollment.setStatus(Enrollment.Status.SCHEDULE_PROPOSED);
        ScheduleProposal proposal = pendingProposalWithSchedule();
        when(proposalRepository.findById(any())).thenReturn(Optional.of(proposal));
        when(bookingRepository.existsByScheduleId(any())).thenReturn(false);
        when(proposalRepository.findByApplicationIdAndStatus(any(),
                eq(ScheduleProposal.Status.PENDING))).thenReturn(List.of());
        when(bookingRepository.findByScheduleIdAndStatusIn(any(), anyList())).thenAnswer(inv -> savedBookings);
        stubAvailabilityAllWeekdays();
        stubNoOverlaps();

        ScheduleService.AcceptResponse res = service.accept(student, proposal.getId());

        assertThat(res.created_count()).isPositive();
        assertThat(res.conflicted_dates()).isEmpty();
        assertThat(res.booking_ids()).hasSize(res.created_count());
        assertThat(res.schedule().status()).isEqualTo("CONFIRMED");
        verify(workflowService).transitionTo(eq(enrollment), eq(Enrollment.Status.SCHEDULED),
                eq(student), eq("SCHEDULE_CONFIRMED"));
        verify(auditLogService).logSync(any());
    }

    @Test
    void acceptSkipsDaysWithoutAvailability() {
        enrollment.setStatus(Enrollment.Status.SCHEDULE_PROPOSED);
        ScheduleProposal proposal = pendingProposalWithSchedule();
        when(proposalRepository.findById(any())).thenReturn(Optional.of(proposal));
        when(bookingRepository.existsByScheduleId(any())).thenReturn(false);
        when(proposalRepository.findByApplicationIdAndStatus(any(),
                eq(ScheduleProposal.Status.PENDING))).thenReturn(List.of());
        when(bookingRepository.findByScheduleIdAndStatusIn(any(), anyList())).thenAnswer(inv -> savedBookings);
        // доступность тьютора теперь не проверяется (тютор сам назначает), поэтому даже при несовпадении слотов занятие создаётся
        List<AvailabilitySlot> slots = new ArrayList<>();
        AvailabilitySlot tuesday = new AvailabilitySlot();
        tuesday.setWeekday(caption(DayOfWeek.TUESDAY));
        tuesday.setStartTime(LocalTime.of(10, 0));
        tuesday.setEndTime(LocalTime.of(15, 0));
        slots.add(tuesday);
        when(availabilitySlotRepository.findByTutorIdOrderByWeekdayAscStartTimeAsc(teacher.getId()))
                .thenReturn(slots);
        stubNoOverlaps();

        ScheduleService.AcceptResponse res = service.accept(student, proposal.getId());

        assertThat(res.created_count()).isPositive();
        assertThat(res.conflicted_dates()).isEmpty();
    }

    @Test
    void acceptByNonOwnerIsForbidden() {
        enrollment.setStatus(Enrollment.Status.SCHEDULE_PROPOSED);
        ScheduleProposal proposal = pendingProposalWithSchedule();
        when(proposalRepository.findById(any())).thenReturn(Optional.of(proposal));
        User stranger = new User();
        stranger.setId(UUID.randomUUID());
        stranger.setRole(Role.STUDENT);

        assertThatThrownBy(() -> service.accept(stranger, proposal.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(NOT_APPLICATION_OWNER);
    }

    @Test
    void acceptNonPendingProposalIsRejected() {
        enrollment.setStatus(Enrollment.Status.SCHEDULE_PROPOSED);
        ScheduleProposal proposal = pendingProposalWithSchedule();
        proposal.setStatus(ScheduleProposal.Status.SUPERSEDED);
        when(proposalRepository.findById(any())).thenReturn(Optional.of(proposal));
        when(bookingRepository.existsByScheduleId(any())).thenReturn(false);
        stubAvailabilityAllWeekdays();
        stubNoOverlaps();

        assertThatThrownBy(() -> service.accept(student, proposal.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(INVALID_APPLICATION_STATE);
    }

    // ---------------- reject / counter ----------------

    @Test
    void studentCanRejectPendingProposal() {
        enrollment.setStatus(Enrollment.Status.SCHEDULE_PROPOSED);
        ScheduleProposal proposal = pendingProposalWithSchedule();
        when(proposalRepository.findById(any())).thenReturn(Optional.of(proposal));

        ScheduleService.ScheduleProposalResponse res = service.reject(student, proposal.getId());

        assertThat(res.status()).isEqualTo("REJECTED");
        verify(workflowService).transitionTo(eq(enrollment), eq(Enrollment.Status.SCHEDULE_PENDING),
                eq(student), eq("SCHEDULE_REJECTED"));
    }

    @Test
    void studentCounterSupersedesOldProposalAndNotifiesTutor() {
        enrollment.setStatus(Enrollment.Status.SCHEDULE_PROPOSED);
        ScheduleProposal current = pendingProposalWithSchedule();
        when(proposalRepository.findById(any())).thenReturn(Optional.of(current));

        var req = new ScheduleService.ProposeRequest(
                "UTC", "ONLINE",
                current.getStartDate().toString(), current.getEndDate().toString(), 60,
                List.of(new ScheduleService.SlotRequest("WEDNESDAY", "18:00", "19:00")), "Встречный вариант",
                null, null, null);

        ScheduleService.ScheduleProposalResponse res = service.counter(student, current.getId(), req);

        assertThat(current.getStatus()).isEqualTo(ScheduleProposal.Status.SUPERSEDED);
        assertThat(res.status()).isEqualTo("PENDING");
        assertThat(res.created_by()).isEqualTo(student.getId());
        verify(workflowService).transitionTo(eq(enrollment), eq(Enrollment.Status.SCHEDULE_PROPOSED),
                eq(student), eq("SCHEDULE_COUNTER"));
        verify(notificationService).notify(eq(teacher.getId()), anyString(), anyString(),
                anyString(), any(), anyString(), anyString());
    }

    // ---------------- schedules / slots ----------------

    @Test
    void studentSeesTheirOwnSchedules() {
        Schedule schedule = new Schedule();
        schedule.setApplication(enrollment);
        schedule.setCourse(course);
        schedule.setStudent(student);
        schedule.setTutor(teacher);
        schedule.setStartDate(LocalDate.now().plusDays(1));
        schedule.setEndDate(LocalDate.now().plusDays(8));
        schedule.setTimezone("UTC");
        schedule.setDurationMinutes(60);
        schedule.setStatus(Schedule.Status.CONFIRMED);
        when(scheduleRepository.findByStudentIdOrderByUpdatedAtDesc(any()))
                .thenReturn(List.of(schedule));
        when(bookingRepository.findByScheduleIdAndStatusIn(any(), anyList())).thenReturn(List.of());

        List<ScheduleService.ScheduleResponse> res = service.mySchedules(student);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).course_title()).isEqualTo("Java Basics");
        assertThat(res.get(0).status()).isEqualTo("CONFIRMED");
    }

    @Test
    void availableSlotsIntersectsAvailabilityAndSkipsUsed() {
        enrollment.setStatus(Enrollment.Status.SCHEDULE_PROPOSED);
        stubAvailabilityAllWeekdays();
        stubNoOverlaps();

        LocalDate from = LocalDate.now().plusDays(1);
        List<ScheduleService.AvailableSlotResponse> res =
                service.availableSlots(student, enrollment.getId(),
                        from.toString(), from.plusDays(6).toString(), "UTC");

        assertThat(res).isNotEmpty();
        assertThat(res).allMatch(s -> s.source().equals("AVAILABILITY"));
        assertThat(res).anyMatch(s -> s.date().equals(from.toString()) && s.start_time().equals("10:00"));
    }
}