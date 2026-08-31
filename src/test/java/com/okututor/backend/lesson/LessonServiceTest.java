package com.okututor.backend.lesson;

import static com.okututor.backend.common.error.ErrorCodes.LESSON_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.okututor.backend.admin.AuditLogService;
import com.okututor.backend.booking.Booking;
import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.course.Course;
import com.okututor.backend.course.CourseRepository;
import com.okututor.backend.notification.NotificationService;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class LessonServiceTest {

    private LessonRepository lessonRepository;
    private BookingRepository bookingRepository;
    private CourseRepository courseRepository;
    private UserService userService;
    private NotificationService notificationService;
    private AuditLogService auditLogService;

    private LessonService service;

    private User teacher;
    private User student;
    private Lesson lesson;
    private Booking booking;

    @BeforeEach
    void setUp() {
        lessonRepository = mock(LessonRepository.class);
        bookingRepository = mock(BookingRepository.class);
        courseRepository = mock(CourseRepository.class);
        userService = mock(UserService.class);
        notificationService = mock(NotificationService.class);
        auditLogService = mock(AuditLogService.class);

        service = new LessonService(lessonRepository, bookingRepository, courseRepository,
                userService, notificationService, auditLogService);

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

        Course course = new Course();
        ReflectionTestUtils.setField(course, "id", UUID.randomUUID());
        course.setTitle("Java Basics");
        course.setTeacher(teacher);

        booking = new Booking();
        booking.setId(UUID.randomUUID());
        booking.setCourse(course);
        booking.setStudent(student);
        booking.setTeacher(teacher);
        booking.setStartAt(Instant.now().plusSeconds(86400));
        booking.setEndAt(booking.getStartAt().plusSeconds(3600));
        booking.setDurationMinutes(60);
        booking.setStatus(Booking.Status.CONFIRMED);

        lesson = new Lesson();
        ReflectionTestUtils.setField(lesson, "id", UUID.randomUUID());
        lesson.setCourse(course);
        lesson.setStudent(student);
        lesson.setTeacher(teacher);
        lesson.setTitle("Java Basics");
        lesson.setStartAt(booking.getStartAt());
        lesson.setEndAt(booking.getEndAt());
        lesson.setStatus(Lesson.Status.SCHEDULED);
        lesson.setBooking(booking);

        when(lessonRepository.findById(any())).thenReturn(Optional.of(lesson));
        when(lessonRepository.save(any(Lesson.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        when(bookingRepository.overlapsParticipantExcluding(
                any(), any(), anyList(), any(Instant.class), any(Instant.class)))
                .thenReturn(false);
        when(lessonRepository.overlapsParticipantExcluding(
                any(), any(), anyList(), any(Instant.class), any(Instant.class)))
                .thenReturn(false);
    }

    private Instant rescheduleStart() {
        return Instant.now().plusSeconds(2 * 86400L).plusSeconds(60);
    }

    // ---------------- reschedule ----------------

    @Test
    void rescheduleMovesLessonAndLinkedBooking() {
        Instant start = rescheduleStart();
        Instant end = start.plusSeconds(3600);

        LessonService.LessonResponse res =
                service.reschedule(teacher, lesson.getId(), new LessonService.RescheduleRequest(start, end));

        assertThat(res.start_at()).isEqualTo(start);
        assertThat(res.end_at()).isEqualTo(end);
        assertThat(lesson.getStartAt()).isEqualTo(start);
        assertThat(lesson.getEndAt()).isEqualTo(end);
        assertThat(booking.getStartAt()).isEqualTo(start);
        assertThat(booking.getEndAt()).isEqualTo(end);
        assertThat(booking.getDurationMinutes()).isEqualTo(60);
        verify(auditLogService).logSync(any());
    }

    @Test
    void rescheduleIntoBusySlotIsConflict() {
        when(bookingRepository.overlapsParticipantExcluding(
                any(), any(), anyList(), any(Instant.class), any(Instant.class)))
                .thenReturn(true);

        Instant start = rescheduleStart();
        assertThatThrownBy(() -> service.reschedule(teacher, lesson.getId(),
                new LessonService.RescheduleRequest(start, start.plusSeconds(3600))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(LESSON_CONFLICT);
    }

    @Test
    void rescheduleFinishedLessonIsConflict() {
        lesson.setStatus(Lesson.Status.COMPLETED);

        Instant start = rescheduleStart();
        assertThatThrownBy(() -> service.reschedule(teacher, lesson.getId(),
                new LessonService.RescheduleRequest(start, start.plusSeconds(3600))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(LESSON_CONFLICT);
    }

    @Test
    void rescheduleTomorrowDefaultDurationIsOneHour() {
        Instant start = rescheduleStart();
        var res = service.reschedule(teacher, lesson.getId(), new LessonService.RescheduleRequest(start, null));

        assertThat(res.end_at()).isEqualTo(start.plusSeconds(3600));
    }

    @Test
    void rescheduleWithInvalidDurationIsRejected() {
        Instant start = rescheduleStart();
        assertThatThrownBy(() -> service.reschedule(teacher, lesson.getId(),
                new LessonService.RescheduleRequest(start, start.plusSeconds(47 * 60L))))
                .isInstanceOf(ApiException.class);
    }

    // ---------------- lifecycle ----------------

    @Test
    void startMarksLessonInProgressAndNotifies() {
        service.start(teacher, lesson.getId());

        assertThat(lesson.getStatus()).isEqualTo(Lesson.Status.IN_PROGRESS);
        verify(notificationService, atLeastOnce()).notify(any(), anyString(), anyString(),
                anyString(), any(), any(), any());
    }

    @Test
    void completeCompletesLessonAndMirrorsBookingStatus() {
        service.complete(student, lesson.getId());

        assertThat(lesson.getStatus()).isEqualTo(Lesson.Status.COMPLETED);
        assertThat(booking.getStatus()).isEqualTo(Booking.Status.COMPLETED);
        verify(bookingRepository).save(booking);
    }

    @Test
    void cancelCancelsLessonAndLinkedBookingWithReason() {
        service.cancel(student, lesson.getId(), "Столкновение с экзаменом");

        assertThat(lesson.getStatus()).isEqualTo(Lesson.Status.CANCELLED);
        assertThat(lesson.getCancelReason()).isEqualTo("Столкновение с экзаменом");
        assertThat(lesson.getCancelledBy()).isSameAs(student);
        assertThat(booking.getStatus()).isEqualTo(Booking.Status.CANCELLED);
        assertThat(booking.getCancelReason()).isEqualTo("Столкновение с экзаменом");
    }

    @Test
    void cancelFinishedLessonIsConflict() {
        lesson.setStatus(Lesson.Status.COMPLETED);

        assertThatThrownBy(() -> service.cancel(teacher, lesson.getId(), "поздно"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("CONFLICT");
    }

    @Test
    void strangerCannotActOnLesson() {
        User stranger = new User();
        stranger.setId(UUID.randomUUID());
        stranger.setRole(Role.STUDENT);

        assertThatThrownBy(() -> service.start(stranger, lesson.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("FORBIDDEN");
    }
}