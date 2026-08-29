package com.okututor.backend.review;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    @Query(value = """
            select r from Review r
            join fetch r.course
            join fetch r.student
            where r.course.id = :courseId and r.hidden = false
            order by r.createdAt desc
            """,
            countQuery = "select count(r) from Review r where r.course.id = :courseId and r.hidden = false")
    Page<Review> findByCourseIdAndHiddenFalseOrderByCreatedAtDesc(@Param("courseId") UUID courseId,
                                                                  Pageable pageable);

    @Query(value = """
            select r from Review r
            join fetch r.course
            join fetch r.student
            order by r.createdAt desc
            """,
            countQuery = "select count(r) from Review r")
    Page<Review> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Optional<Review> findByCourseIdAndStudentId(UUID courseId, UUID studentId);

    Optional<Review> findByIdAndStudentId(UUID id, UUID studentId);

    @Query("""
            select count(b) > 0 from com.okututor.backend.booking.Booking b
            where b.course.id = :courseId and b.student.id = :studentId and b.status = com.okututor.backend.booking.Booking.Status.COMPLETED
            """)
    boolean existsCompletedBooking(@Param("courseId") UUID courseId, @Param("studentId") UUID studentId);

    interface ReviewAggregate {
        Double getAvgRating();
        Long getCount();
    }

    @Query("""
            select avg(r.rating) as avgRating, count(r) as count
            from Review r where r.course.id = :courseId and r.hidden = false
            """)
    ReviewAggregate aggregateForCourse(@Param("courseId") UUID courseId);

    /** средняя оценка, поставленная студентом (прогресс студента). */
    @Query("select coalesce(avg(r.rating), 0.0) from Review r where r.student.id = :studentId")
    double averageRatingGiven(@Param("studentId") UUID studentId);

    /** средняя оценка тьютора по отзывам на его курсы. */
    @Query("select coalesce(avg(r.rating), 0.0) from Review r " +
            "where r.course.teacher.id = :teacherId and r.hidden = false")
    double averageRatingForTutor(@Param("teacherId") UUID teacherId);
}
