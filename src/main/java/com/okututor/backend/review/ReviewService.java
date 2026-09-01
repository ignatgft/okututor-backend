package com.okututor.backend.review;

import com.okututor.backend.booking.Booking;
import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.course.Course;
import com.okututor.backend.course.CourseRepository;
import com.okututor.backend.course.CourseService;
import com.okututor.backend.lesson.MeetingSessionRepository;
import com.okututor.backend.user.User;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService {

    public record ReviewResponse(
            UUID id,
            UUID course_id,
            int rating,
            String comment,
            UUID student_id,
            String student_name,
            boolean hidden,
            Instant created_at
    ) {}

    public record ReviewRequest(Integer rating, String comment) {}

    public record CanReviewResponse(
            boolean eligible,
            boolean has_attended,
            boolean already_reviewed
    ) {}

    private final ReviewRepository repository;
    private final BookingRepository bookingRepository;
    private final CourseService courseService;
    private final CourseRepository courseRepository;
    private final MeetingSessionRepository meetingSessionRepository;

    public ReviewService(ReviewRepository repository,
                         BookingRepository bookingRepository,
                         CourseService courseService,
                         CourseRepository courseRepository,
                         MeetingSessionRepository meetingSessionRepository) {
        this.repository = repository;
        this.bookingRepository = bookingRepository;
        this.courseService = courseService;
        this.courseRepository = courseRepository;
        this.meetingSessionRepository = meetingSessionRepository;
    }

    /** публичный список — скрытые отзывы исключены. */
    @Transactional(readOnly = true)
    public Page<ReviewResponse> forCourse(UUID courseId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return repository.findByCourseIdAndHiddenFalseOrderByCreatedAtDesc(courseId, pageable).map(this::toResponse);
    }

    /** основной сценарий из ReviewModal: отзыв на конкретный COMPLETED booking. */
    @Transactional
    public ReviewResponse createForBooking(User student, UUID courseId, UUID bookingId,
                                           Integer rating, String comment) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> ApiException.notFound("Booking not found"));
        if (!student.getId().equals(booking.getStudentId())) {
            throw ApiException.forbidden("Not your booking");
        }
        if (booking.getCourse() == null || !courseId.equals(booking.getCourse().getId())) {
            throw ApiException.validation("Booking does not belong to this course");
        }
        if (booking.getStatus() != Booking.Status.COMPLETED) {
            throw ApiException.forbidden(com.okututor.backend.common.error.ErrorCodes.REVIEW_NOT_ALLOWED,
                    "You can review the course after the lesson is completed");
        }
        return toResponse(persistReview(student, courseId, rating, comment, booking));
    }

    /** свободный эндпоинт: требует COMPLETED-бронь или факт посещения (meeting_sessions). */
    @Transactional
    public ReviewResponse create(User student, UUID courseId, Integer rating, String comment) {
        boolean hasAttended = meetingSessionRepository.hasAttendedLesson(courseId, student.getId());
        boolean hasCompletedBooking = repository.existsCompletedBooking(courseId, student.getId())
                || bookingRepository.existsByCourseIdAndStudentIdAndStatus(
                        courseId, student.getId(), Booking.Status.COMPLETED);
        if (!hasAttended && !hasCompletedBooking) {
            throw ApiException.forbidden(com.okututor.backend.common.error.ErrorCodes.REVIEW_NOT_ALLOWED,
                    "You can review the course after the lesson is completed");
        }
        return toResponse(persistReview(student, courseId, rating, comment, null));
    }

    @Transactional(readOnly = true)
    public CanReviewResponse canReview(UUID studentId, UUID courseId) {
        boolean hasAttended = meetingSessionRepository.hasAttendedLesson(courseId, studentId);
        boolean hasCompletedBooking = repository.existsCompletedBooking(courseId, studentId)
                || bookingRepository.existsByCourseIdAndStudentIdAndStatus(
                        courseId, studentId, Booking.Status.COMPLETED);
        boolean eligible = (hasAttended || hasCompletedBooking)
                && repository.findByCourseIdAndStudentId(courseId, studentId).isEmpty();
        boolean alreadyReviewed = repository.findByCourseIdAndStudentId(courseId, studentId).isPresent();
        return new CanReviewResponse(
            eligible,
            hasAttended || hasCompletedBooking,
            alreadyReviewed
        );
    }

    private Review persistReview(User student, UUID courseId, Integer rating, String comment, Booking booking) {
        Course course = courseService.requireById(courseId);
        if (rating == null || rating < 1 || rating > 5) {
            throw new com.okututor.backend.common.error.FieldValidationException(
                    Map.of("rating", "Rating must be between 1 and 5"));
        }
        if (repository.findByCourseIdAndStudentId(courseId, student.getId()).isPresent()) {
            throw ApiException.conflict("You have already reviewed this course");
        }

        Review review = new Review();
        review.setCourse(course);
        review.setStudent(student);
        review.setBooking(booking);
        review.setRating(rating);
        review.setComment(comment);

        try {
            review = repository.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("You have already reviewed this course");
        }
        refreshAggregate(courseId);
        return review;
    }

    // --- модерация (админ) ---

    @Transactional(readOnly = true)
    public Page<Review> listAll(int page, int size) {
        return repository.findAllByOrderByCreatedAtDesc(
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
    }

    @Transactional
    public void setHidden(UUID reviewId, boolean hidden) {
        Review review = repository.findById(reviewId)
                .orElseThrow(() -> ApiException.notFound("Review not found"));
        review.setHidden(hidden);
        repository.save(review);
        if (review.getCourse() != null) {
            refreshAggregate(review.getCourse().getId());
        }
    }

    /** агрегат рейтинга пишется одним UPDATE: без read-modify-write гонок. */
    private void refreshAggregate(UUID courseId) {
        ReviewRepository.ReviewAggregate agg = repository.aggregateForCourse(courseId);
        Double avg = agg != null ? agg.getAvgRating() : null;
        long count = agg != null && agg.getCount() != null ? agg.getCount() : 0L;
        BigDecimal avgScaled = avg == null ? null : BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP);
        courseRepository.refreshRatingAggregate(courseId, avgScaled, count, Instant.now());
    }

    private ReviewResponse toResponse(Review r) {
        User student = r.getStudent();
        return new ReviewResponse(
                r.getId(),
                r.getCourse() != null ? r.getCourse().getId() : null,
                r.getRating(),
                r.getComment(),
                student != null ? student.getId() : null,
                student != null ? student.getFullName() : null,
                r.isHidden(),
                r.getCreatedAt());
    }

    static Instant now() {
        return Instant.now();
    }
}
