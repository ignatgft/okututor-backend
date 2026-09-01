package com.okututor.backend.enrollment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.okututor.backend.booking.Booking;
import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.admin.AuditLogService;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.course.Course;
import com.okututor.backend.course.CourseService;
import com.okututor.backend.messaging.MessagingService;
import com.okututor.backend.notification.NotificationService;
import com.okututor.backend.tutors.AvailabilitySlot;
import com.okututor.backend.tutors.AvailabilitySlotRepository;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EnrollmentSeriesTest {

    private EnrollmentRepository enrollmentRepository;
    private CourseService courseService;
    private BookingRepository bookingRepository;
    private NotificationService notificationService;
    private MessagingService messagingService;
    private AvailabilitySlotRepository availabilitySlotRepository;
    private AuditLogService auditLogService;

    private EnrollmentService service;

    private User teacher;
    private User student;
    private Course course;
    private Enrollment enrollment;
    private UUID enrollmentId;

    @BeforeEach
    void setUp() {
        enrollmentRepository = mock(EnrollmentRepository.class);
        courseService = mock(CourseService.class);
        bookingRepository = mock(BookingRepository.class);
        notificationService = mock(NotificationService.class);
        messagingService = mock(MessagingService.class);
        availabilitySlotRepository = mock(AvailabilitySlotRepository.class);
        auditLogService = mock(AuditLogService.class);
        com.okututor.backend.lesson.LessonRepository lessonRepository = mock(com.okututor.backend.lesson.LessonRepository.class);

        service = new EnrollmentService(
                enrollmentRepository, courseService, bookingRepository, lessonRepository,
                notificationService, messagingService, availabilitySlotRepository,
                auditLogService
        );

        teacher = new User();
        teacher.setId(UUID.randomUUID());
        teacher.setRole(Role.TUTOR);
        teacher.setFirstName("Tut");
        teacher.setLastName("Or");

        student = new User();
        student.setId(UUID.randomUUID());
        student.setRole(Role.STUDENT);
        student.setFirstName("Stu");
        student.setLastName("Dent");

        course = new Course();
        course.setTitle("Java Series");
        course.setTeacher(teacher);

        enrollment = new Enrollment();
        enrollmentId = UUID.randomUUID();
        enrollment.setCourse(course);
        enrollment.setStudent(student);

        when(enrollmentRepository.findById(enrollmentId)).thenReturn(Optional.of(enrollment));
        when(bookingRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<Booking> list = inv.getArgument(0);
            for (Booking b : list) {
                if (b.getId() == null) {
                    b.setId(UUID.randomUUID());
                }
            }
            return list;
        });
    }

    private void stubAvailabilityForDays(List<String> weekdays, LocalTime start, LocalTime end) {
        List<AvailabilitySlot> slots = weekdays.stream().map(w -> {
            AvailabilitySlot s = new AvailabilitySlot();
            s.setWeekday(w);
            s.setStartTime(start);
            s.setEndTime(end);
            return s;
        }).toList();
        when(availabilitySlotRepository.findByTutorIdOrderByWeekdayAscStartTimeAsc(teacher.getId()))
                .thenReturn(slots);
    }

    @Test
    void acceptsAndSchedulesSeries_withValidAvailabilityAndNoConflicts() {
        LocalDate start = LocalDate.now().plusDays(30);
        LocalDate end = start.plusDays(14);
        String w1 = start.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String w2 = start.plusDays(1).getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);

        stubAvailabilityForDays(List.of(w1, w2), LocalTime.of(10, 0), LocalTime.of(15, 0));

        var req = new EnrollmentService.AcceptAndScheduleRequest(
                null, null, null, null,
                new EnrollmentService.SeriesRequest(
                        start.toString(), end.toString(), "12:00",
                        List.of(w1, w2), 60, "UTC"
                )
        );

        var res = service.acceptAndSchedule(teacher, enrollmentId, req);

        assertThat(res.created_count()).isPositive();
        assertThat(res.conflicted_dates()).isEmpty();
        assertThat(res.booking_id()).isNotNull();
        assertThat(res.booking_status()).isEqualTo("CONFIRMED");
        assertThat(res.enrollment().status()).isEqualTo("ACCEPTED");
        verify(notificationService).notify(any(), any(), any(), any(), any());
    }

    @Test
    void skipsConflictingDates_whenAvailabilityMissingOnSomeDays() {
        LocalDate start = LocalDate.now().plusDays(30);
        LocalDate end = start.plusDays(7);
        String w1 = start.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String w2 = start.plusDays(1).getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);

        // stub only w2, so w1 (start date) conflicts
        stubAvailabilityForDays(List.of(w2), LocalTime.of(10, 0), LocalTime.of(15, 0));

        var req = new EnrollmentService.AcceptAndScheduleRequest(
                null, null, null, null,
                new EnrollmentService.SeriesRequest(
                        start.toString(), end.toString(), "12:00",
                        List.of(w1, w2), 60, "UTC"
                )
        );

        var res = service.acceptAndSchedule(teacher, enrollmentId, req);

        assertThat(res.created_count()).isPositive();
        assertThat(res.conflicted_dates()).contains(start.toString());
    }

    @Test
    void requiresWeekdays_throwsValidation() {
        LocalDate start = LocalDate.now().plusDays(30);
        LocalDate end = start.plusDays(14);
        var req = new EnrollmentService.AcceptAndScheduleRequest(
                null, null, null, null,
                new EnrollmentService.SeriesRequest(
                        start.toString(), end.toString(), "12:00",
                        List.of(), 60, "UTC"
                )
        );

        assertThatThrownBy(() -> service.acceptAndSchedule(teacher, enrollmentId, req))
                .isInstanceOf(ApiException.class);
    }
}
