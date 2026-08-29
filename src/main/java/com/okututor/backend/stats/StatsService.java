package com.okututor.backend.stats;

import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.booking.Booking.Status;
import com.okututor.backend.enrollment.Enrollment;
import com.okututor.backend.enrollment.EnrollmentRepository;
import com.okututor.backend.review.ReviewRepository;
import com.okututor.backend.user.User;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatsService {

    public record StudentStats(
            long completed_lessons,
            long upcoming_lessons,
            double total_hours,
            long courses_count,
            double average_rating_given,
            List<Map<String, Object>> by_month
    ) {}

    public record TutorStats(
            long students_count,
            long completed_lessons,
            long upcoming,
            double total_hours,
            double average_rating,
            long pending_requests
    ) {}

    private final BookingRepository bookingRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final ReviewRepository reviewRepository;

    public StatsService(BookingRepository bookingRepository,
                        EnrollmentRepository enrollmentRepository,
                        ReviewRepository reviewRepository) {
        this.bookingRepository = bookingRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.reviewRepository = reviewRepository;
    }

    @Transactional(readOnly = true)
    public StudentStats studentStats(User student) {
        long completed = bookingRepository.countByStudentIdAndStatusAndStartAtGreaterThanEqual(
                student.getId(), Status.COMPLETED, Instant.EPOCH);
        long upcoming = bookingRepository.countByStudentIdAndStatusAndStartAtGreaterThanEqual(
                student.getId(), Status.CONFIRMED, Instant.now());
        long minutes = bookingRepository.totalMinutesAsStudent(student.getId());
        long coursesCount = enrollmentRepository.countDistinctCourses(
                student.getId(), List.of(Enrollment.Status.ACCEPTED));
        double avgGiven = Math.round(reviewRepository.averageRatingGiven(student.getId()) * 100.0) / 100.0;

        // сводка по месяцам (завершённые брони)
        Map<YearMonth, Long> byMonth = new TreeMap<>();
        for (var b : bookingRepository.completedForStudent(student.getId())) {
            YearMonth ym = YearMonth.from(b.getStartAt().atZone(ZoneOffset.UTC));
            byMonth.merge(ym, 1L, Long::sum);
        }
        List<Map<String, Object>> months = byMonth.entrySet().stream()
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("month", e.getKey().toString()); // 2026-08
                    row.put("completed", e.getValue());
                    return row;
                })
                .toList();

        return new StudentStats(completed, upcoming, hours(minutes), coursesCount, avgGiven, months);
    }

    @Transactional(readOnly = true)
    public TutorStats tutorStats(User tutor) {
        long students = bookingRepository.countDistinctStudents(
                tutor.getId(), List.of(Status.CONFIRMED, Status.COMPLETED));
        long completed = bookingRepository.countByTeacherIdAndStatus(tutor.getId(), Status.COMPLETED);
        long upcoming = bookingRepository.countByTeacherIdAndStatusAndStartAtGreaterThanEqual(
                tutor.getId(), Status.CONFIRMED, Instant.now());
        long minutes = bookingRepository.totalMinutesAsTeacher(tutor.getId());
        double avgRating = Math.round(reviewRepository.averageRatingForTutor(tutor.getId()) * 100.0) / 100.0;
        long pending = enrollmentRepository.countByCourseTeacherIdAndStatus(tutor.getId(), Enrollment.Status.PENDING);

        return new TutorStats(students, completed, upcoming, hours(minutes), avgRating, pending);
    }

    private static double hours(long minutes) {
        return Math.round((minutes / 60.0) * 100.0) / 100.0;
    }
}
