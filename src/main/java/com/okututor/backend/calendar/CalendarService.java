package com.okututor.backend.calendar;

import com.okututor.backend.booking.Booking;
import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.booking.ScheduleParser;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.FieldValidationException;
import com.okututor.backend.course.Course;
import com.okututor.backend.lesson.Lesson;
import com.okututor.backend.lesson.LessonRepository;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Агрегированный календарь: брони + самостоятельные уроки за диапазон [from, to).
 * Единая точка входа `GET /api/v1/calendar` для ролей STUDENT/TUTOR/ADMIN.
 *
 * Все записи приходят из БД одним-двумя запросами с join fetch (без N+1),
 * диапазон ограничен 90 днями, таймзона — IANA (по умолчанию UTC).
 */
@Service
public class CalendarService {

    public static final int MAX_RANGE_DAYS = 90;

    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public record CalendarItem(
            UUID id,
            String type,
            String title,
            UUID course_id,
            String course_title,
            UUID booking_id,
            UUID lesson_id,
            String counterpart,
            Instant start_at,
            Instant end_at,
            String status,
            boolean joinable,
            boolean cancelled,
            String location_type,
            String local_start,
            String local_end,
            String timezone
    ) {}

    private final BookingRepository bookingRepository;
    private final LessonRepository lessonRepository;

    public CalendarService(BookingRepository bookingRepository, LessonRepository lessonRepository) {
        this.bookingRepository = bookingRepository;
        this.lessonRepository = lessonRepository;
    }

    @Transactional(readOnly = true)
    public List<CalendarItem> calendar(User viewer, Instant from, Instant to, String timezone) {
        requireRange(from, to);
        ZoneId zone = ScheduleParser.parseZone(timezone);

        boolean admin = viewer.getRole() == Role.ADMIN || viewer.getRole() == Role.SUPER_ADMIN;
        UUID userId = admin ? null : viewer.getId();

        List<CalendarItem> items = new ArrayList<>();
        if (admin) {
            mapBookings(items, bookingRepository.calendarAll(from, to), userId, zone);
            mapLessons(items, lessonRepository.calendarAll(from, to), userId, zone);
        } else if (viewer.getRole() == Role.STUDENT) {
            mapBookings(items, bookingRepository.calendarByStudent(userId, from, to), userId, zone);
            mapLessons(items, lessonRepository.calendarByStudent(userId, from, to), userId, zone);
        } else if (viewer.getRole() == Role.TUTOR) {
            mapBookings(items, bookingRepository.calendarByTeacher(userId, from, to), userId, zone);
            mapLessons(items, lessonRepository.calendarByTeacher(userId, from, to), userId, zone);
        } else {
            throw ApiException.forbidden("This role cannot view the calendar");
        }

        items.sort(Comparator.comparing(CalendarItem::start_at));
        return items;
    }

    private void mapBookings(List<CalendarItem> items, List<Booking> bookings, UUID viewerId, ZoneId zone) {
        for (Booking b : bookings) {
            Instant start = b.getStartAt();
            Instant end = b.getEndAt();
            Booking.Status status = b.getStatus();
            boolean cancelled = status == Booking.Status.CANCELLED;
            boolean joinable = !cancelled
                    && (status == Booking.Status.PENDING || status == Booking.Status.CONFIRMED)
                    && !end.isBefore(Instant.now());
            items.add(new CalendarItem(
                    b.getId(),
                    "BOOKING",
                    title(b),
                    courseId(b),
                    courseTitle(b),
                    b.getId(),
                    null,
                    counterpart(b, viewerId),
                    start,
                    end,
                    status.name(),
                    joinable,
                    cancelled,
                    locationType(b),
                    formatLocal(start, zone),
                    formatLocal(end, zone),
                    zone.getId()));
        }
    }

    private void mapLessons(List<CalendarItem> items, List<Lesson> lessons, UUID viewerId, ZoneId zone) {
        for (Lesson l : lessons) {
            Instant start = l.getStartAt();
            // урок без брони не хранит длительность — по умолчанию платформенные 60 минут
            Instant end = start == null ? null : start.plus(Duration.ofMinutes(60));
            Lesson.Status status = l.getStatus();
            boolean cancelled = status == Lesson.Status.CANCELLED;
            boolean joinable = !cancelled
                    && (status == Lesson.Status.SCHEDULED || status == Lesson.Status.IN_PROGRESS)
                    && end != null && !end.isBefore(Instant.now());
            Course course = l.getCourse();
            items.add(new CalendarItem(
                    l.getId(),
                    "LESSON",
                    l.getTitle(),
                    course != null ? course.getId() : null,
                    course != null ? course.getTitle() : null,
                    null,
                    l.getId(),
                    counterpart(l, viewerId),
                    start,
                    end,
                    status.name(),
                    joinable,
                    cancelled,
                    l.getCourse() != null ? l.getCourse().getLocationType().name() : null,
                    formatLocal(start, zone),
                    formatLocal(end, zone),
                    zone.getId()));
        }
    }

    private static String title(Booking b) {
        Course course = b.getCourse();
        return course != null ? course.getTitle() : "Занятие";
    }

    private static UUID courseId(Booking b) {
        return b.getCourse() != null ? b.getCourse().getId() : null;
    }

    private static String courseTitle(Booking b) {
        return b.getCourse() != null ? b.getCourse().getTitle() : null;
    }

    private static String locationType(Booking b) {
        return b.getCourse() != null ? b.getCourse().getLocationType().name() : null;
    }

    private static String counterpart(Booking b, UUID viewerId) {
        return counterpart(viewerId, userName(b.getStudent()), userName(b.getTeacher()),
                studentId(b), teacherId(b));
    }

    private static String counterpart(Lesson l, UUID viewerId) {
        return counterpart(viewerId, userName(l.getStudent()), userName(l.getTeacher()),
                studentId(l), teacherId(l));
    }

    private static UUID studentId(Booking b) {
        return b.getStudent() != null ? b.getStudent().getId() : null;
    }

    private static UUID teacherId(Booking b) {
        return b.getTeacher() != null ? b.getTeacher().getId() : null;
    }

    private static UUID studentId(Lesson l) {
        return l.getStudent() != null ? l.getStudent().getId() : null;
    }

    private static UUID teacherId(Lesson l) {
        return l.getTeacher() != null ? l.getTeacher().getId() : null;
    }

    /**
     * Собеседник относительно зрителя: если зритель = студент → тьютор, и наоборот;
     * для ADMIN (нет id) → «студент / тьютор», чтобы видеть обоих.
     */
    private static String counterpart(UUID viewerId, String studentName, String teacherName,
                                      UUID studentId, UUID teacherId) {
        if (viewerId == null) {
            boolean blank = (studentName == null || studentName.isBlank())
                    && (teacherName == null || teacherName.isBlank());
            return blank ? null : studentName + " / " + teacherName;
        }
        if (viewerId.equals(studentId)) {
            return teacherName;
        }
        if (viewerId.equals(teacherId)) {
            return studentName;
        }
        return studentName + " / " + teacherName;
    }

    private static String userName(User u) {
        return u != null ? u.getFullName() : null;
    }

    private static String formatLocal(Instant instant, ZoneId zone) {
        return instant == null ? null : ISO_LOCAL.format(instant.atZone(zone));
    }

    private void requireRange(Instant from, Instant to) {
        if (from == null || to == null) {
            throw new FieldValidationException(Map.of("from", "from (ISO-8601) is required",
                    "to", "to (ISO-8601) is required"));
        }
        if (!from.isBefore(to)) {
            throw new FieldValidationException(Map.of("from", "from must be before to"));
        }
        long days = Duration.between(from, to).toDays();
        if (days > MAX_RANGE_DAYS) {
            throw new FieldValidationException(Map.of("to",
                    "Range is too large, max " + MAX_RANGE_DAYS + " days"));
        }
    }
}
