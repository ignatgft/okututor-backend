package com.okututor.backend.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.okututor.backend.booking.Booking;
import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.course.Course;
import com.okututor.backend.course.CourseService;
import com.okututor.backend.course.CourseRepository;
import com.okututor.backend.lesson.MeetingSessionRepository;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ReviewServiceTest {

    private ReviewRepository reviewRepository;
    private BookingRepository bookingRepository;
    private CourseService courseService;
    private CourseRepository courseRepository;
    private MeetingSessionRepository meetingSessionRepository;

    private ReviewService service;

    private User student;
    private Course course;
    private UUID courseId;

    @BeforeEach
    void setUp() {
        reviewRepository = mock(ReviewRepository.class);
        bookingRepository = mock(BookingRepository.class);
        courseService = mock(CourseService.class);
        courseRepository = mock(CourseRepository.class);
        meetingSessionRepository = mock(MeetingSessionRepository.class);
        service = new ReviewService(reviewRepository, bookingRepository, courseService, courseRepository, meetingSessionRepository);

        student = new User();
        student.setId(UUID.randomUUID());
        student.setRole(Role.STUDENT);
        student.setFirstName("Stu");
        student.setLastName("Dent");

        course = new Course();
        courseId = UUID.randomUUID();
        ReflectionTestUtils.setField(course, "id", courseId);
        course.setTitle("Java Basics");
        when(courseService.requireById(courseId)).thenReturn(course);
        when(reviewRepository.findByCourseIdAndStudentId(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void canReview_falseWhenNoCompletedBookingOrAttendance() {
        when(meetingSessionRepository.hasAttendedLesson(courseId, student.getId())).thenReturn(false);
        when(reviewRepository.existsCompletedBooking(courseId, student.getId())).thenReturn(false);
        when(bookingRepository.existsByCourseIdAndStudentIdAndStatus(any(), any(), any())).thenReturn(false);

        ReviewService.CanReviewResponse res = service.canReview(student.getId(), courseId);
        assertThat(res.eligible()).isFalse();
        assertThat(res.has_attended()).isFalse();
        assertThat(res.already_reviewed()).isFalse();
    }

    @Test
    void canReview_trueAfterCompletedBookingEvenWithoutAttendance() {
        when(meetingSessionRepository.hasAttendedLesson(courseId, student.getId())).thenReturn(false);
        when(reviewRepository.existsCompletedBooking(courseId, student.getId())).thenReturn(true);
        when(bookingRepository.existsByCourseIdAndStudentIdAndStatus(any(), any(), any())).thenReturn(false);
        when(reviewRepository.findByCourseIdAndStudentId(courseId, student.getId())).thenReturn(Optional.empty());

        ReviewService.CanReviewResponse res = service.canReview(student.getId(), courseId);
        assertThat(res.eligible()).isTrue();
        assertThat(res.has_attended()).isTrue();
    }

    @Test
    void canReview_trueAfterAttendedLesson() {
        when(meetingSessionRepository.hasAttendedLesson(courseId, student.getId())).thenReturn(true);
        when(reviewRepository.findByCourseIdAndStudentId(courseId, student.getId())).thenReturn(Optional.empty());

        ReviewService.CanReviewResponse res = service.canReview(student.getId(), courseId);
        assertThat(res.eligible()).isTrue();
        assertThat(res.has_attended()).isTrue();
    }

    @Test
    void canReview_falseWhenAlreadyReviewed() {
        when(meetingSessionRepository.hasAttendedLesson(courseId, student.getId())).thenReturn(true);
        when(reviewRepository.findByCourseIdAndStudentId(courseId, student.getId()))
                .thenReturn(Optional.of(new Review()));

        ReviewService.CanReviewResponse res = service.canReview(student.getId(), courseId);
        assertThat(res.eligible()).isFalse();
        assertThat(res.already_reviewed()).isTrue();
    }

    @Test
    void create_throwsReviewNotAllowedWithoutCompletedBookingOrAttendance() {
        when(meetingSessionRepository.hasAttendedLesson(courseId, student.getId())).thenReturn(false);
        when(reviewRepository.existsCompletedBooking(courseId, student.getId())).thenReturn(false);
        when(bookingRepository.existsByCourseIdAndStudentIdAndStatus(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.create(student, courseId, 5, "Great!"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("REVIEW_NOT_ALLOWED");
    }

    @Test
    void createForBooking_requiresCompletedStatus() {
        Booking booking = new Booking();
        booking.setId(UUID.randomUUID());
        booking.setStudent(student);
        booking.setTeacher(student); // teacher check bypassed via student mismatch first
        booking.setCourse(course);
        booking.setStatus(Booking.Status.CONFIRMED);
        when(bookingRepository.findById(booking.getId())).thenReturn(Optional.of(booking));

        // student's booking but not COMPLETED -> FORBIDDEN
        assertThatThrownBy(() -> service.createForBooking(student, courseId, booking.getId(), 5, "nice"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("REVIEW_NOT_ALLOWED");
    }
}
