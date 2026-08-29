package com.okututor.backend.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.okututor.backend.booking.Booking;
import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.common.error.FieldValidationException;
import com.okututor.backend.course.Course;
import com.okututor.backend.lesson.Lesson;
import com.okututor.backend.lesson.LessonRepository;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CalendarServiceTest {

    private BookingRepository bookingRepository;
    private LessonRepository lessonRepository;
    private CalendarService service;

    private final User student = user(Role.STUDENT, "Stu", "Dent");
    private final User teacher = user(Role.TUTOR, "Tut", "Or");
    private final User admin = user(Role.SUPER_ADMIN, "Big", "Admin");

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRepository.class);
        lessonRepository = mock(LessonRepository.class);
        service = new CalendarService(bookingRepository, lessonRepository);
    }

    @Test
    void studentSeesOnlyTheirBookings_withCounterpartAsTeacher() {
        Booking b = booking(teacher, student, Booking.Status.CONFIRMED,
                "2026-09-04T08:00:00Z", "2026-09-04T09:00:00Z");
        when(bookingRepository.calendarByStudent(eq(student.getId()), any(), any()))
                .thenReturn(List.of(b));

        List<CalendarService.CalendarItem> items = service.calendar(student,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"), null);

        assertThat(items).hasSize(1);
        CalendarService.CalendarItem item = items.get(0);
        assertThat(item.type()).isEqualTo("BOOKING");
        assertThat(item.booking_id()).isEqualTo(b.getId());
        assertThat(item.counterpart()).isEqualTo("Tut Or");
        assertThat(item.start_at()).isEqualTo(Instant.parse("2026-09-04T08:00:00Z"));
        assertThat(item.end_at()).isEqualTo(Instant.parse("2026-09-04T09:00:00Z"));
        assertThat(item.status()).isEqualTo("CONFIRMED");
        assertThat(item.joinable()).isTrue();
        assertThat(item.cancelled()).isFalse();
    }

    @Test
    void tutorSeesTheirBookings_withCounterpartAsStudent() {
        Booking b = booking(teacher, student, Booking.Status.PENDING,
                "2026-09-05T10:00:00Z", "2026-09-05T11:00:00Z");
        when(bookingRepository.calendarByTeacher(eq(teacher.getId()), any(), any()))
                .thenReturn(List.of(b));

        List<CalendarService.CalendarItem> items = service.calendar(teacher,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"), "Asia/Bishkek");

        assertThat(items).singleElement().satisfies(it -> {
            assertThat(it.counterpart()).isEqualTo("Stu Dent");
            assertThat(it.timezone()).isEqualTo("Asia/Bishkek");
        });
    }

    @Test
    void standaloneLessonWithoutBookingAppearsAsLessonItem() {
        Lesson l = lesson(teacher, student, Lesson.Status.SCHEDULED, "2026-09-06T15:00:00Z");
        when(lessonRepository.calendarByStudent(eq(student.getId()), any(), any()))
                .thenReturn(List.of(l));

        List<CalendarService.CalendarItem> items = service.calendar(student,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"), null);

        assertThat(items).singleElement().satisfies(it -> {
            assertThat(it.type()).isEqualTo("LESSON");
            assertThat(it.lesson_id()).isEqualTo(l.getId());
            assertThat(it.booking_id()).isNull();
            assertThat(it.end_at()).isEqualTo(Instant.parse("2026-09-06T16:00:00Z"));
        });
    }

    @Test
    void adminSeesAllBookingsWithBothParties() {
        Booking b = booking(teacher, student, Booking.Status.COMPLETED,
                "2026-09-02T09:00:00Z", "2026-09-02T10:00:00Z");
        when(bookingRepository.calendarAll(any(), any())).thenReturn(List.of(b));

        List<CalendarService.CalendarItem> items = service.calendar(admin,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"), null);

        assertThat(items).singleElement().satisfies(it -> {
            assertThat(it.counterpart()).contains("Tut Or");
            assertThat(it.joinable()).isFalse();
            assertThat(it.cancelled()).isFalse();
        });
    }

    @Test
    void itemsAreSortedByStart() {
        Booking later = booking(teacher, student, Booking.Status.CONFIRMED,
                "2026-09-10T10:00:00Z", "2026-09-10T11:00:00Z");
        Booking earlier = booking(teacher, student, Booking.Status.CONFIRMED,
                "2026-09-03T10:00:00Z", "2026-09-03T11:00:00Z");
        when(bookingRepository.calendarByStudent(eq(student.getId()), any(), any()))
                .thenReturn(List.of(later, earlier));

        List<CalendarService.CalendarItem> items = service.calendar(student,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"), null);

        assertThat(items).extracting(CalendarService.CalendarItem::start_at)
                .containsExactly(Instant.parse("2026-09-03T10:00:00Z"),
                        Instant.parse("2026-09-10T10:00:00Z"));
    }

    @Test
    void cancelledBookingMarkedCancelled() {
        Booking b = booking(teacher, student, Booking.Status.CANCELLED,
                "2026-09-04T08:00:00Z", "2026-09-04T09:00:00Z");
        when(bookingRepository.calendarByStudent(eq(student.getId()), any(), any()))
                .thenReturn(List.of(b));

        List<CalendarService.CalendarItem> items = service.calendar(student,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"), null);

        assertThat(items).singleElement().satisfies(it -> {
            assertThat(it.cancelled()).isTrue();
            assertThat(it.joinable()).isFalse();
        });
    }

    @Test
    void rejectsMissingOrInvertedOrTooWideRange() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> service.calendar(student, null, now, null))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.calendar(student, now, now.minusSeconds(3600), null))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.calendar(student, now, now.plus(Duration.ofDays(91)), null))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    void rejectsUnknownTimezone() {
        Instant from = Instant.now().minus(Duration.ofDays(1));
        Instant to = Instant.now().plus(Duration.ofDays(1));
        assertThatThrownBy(() -> service.calendar(student, from, to, "Mars/Olympus"))
                .isInstanceOf(com.okututor.backend.common.error.ApiException.class);
    }

    // ---- helpers ----

    private static User user(Role role, String first, String last) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(role);
        u.setFirstName(first);
        u.setLastName(last);
        return u;
    }

    private static Course course(User teacher) {
        Course c = new Course();
        c.setTeacher(teacher);
        c.setTitle("Java Basics");
        return c;
    }

    private static Booking booking(User t, User s, Booking.Status status,
                                   String start, String end) {
        Booking b = new Booking();
        b.setCourse(course(t));
        b.setTeacher(t);
        b.setStudent(s);
        b.setStatus(status);
        b.setStartAt(Instant.parse(start));
        b.setEndAt(Instant.parse(end));
        b.setDurationMinutes(60);
        return b;
    }

    private static Lesson lesson(User t, User s, Lesson.Status status, String start) {
        Lesson l = new Lesson();
        l.setTeacher(t);
        l.setStudent(s);
        l.setStatus(status);
        l.setStartAt(Instant.parse(start));
        l.setTitle("Java Basics");
        l.setCourse(course(t));
        return l;
    }
}
