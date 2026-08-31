package com.okututor.backend.enrollment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EnrollmentRepository extends JpaRepository<Enrollment, UUID> {

    Optional<Enrollment> findByCourseIdAndStudentIdAndStatusIn(UUID courseId, UUID studentId,
                                                               Iterable<Enrollment.Status> statuses);

    /** последняя по времени запись (историческая или текущая); не падает на дублях REJECTED/CANCELLED. */
    Optional<Enrollment> findFirstByCourseIdAndStudentIdOrderByCreatedAtDesc(UUID courseId, UUID studentId);

    @Query(value = """
            select e from Enrollment e
            join fetch e.course
            join fetch e.student
            where e.student.id = :studentId
            order by e.updatedAt desc
            """,
            countQuery = "select count(e) from Enrollment e where e.student.id = :studentId")
    Page<Enrollment> findByStudentIdOrderByUpdatedAtDesc(@Param("studentId") UUID studentId, Pageable pageable);

    @Query("""
            select case when count(e) > 0 then true else false end
            from Enrollment e where e.course.id = :courseId and e.student.id = :studentId and e.status = :status
            """)
    boolean existsByCourseIdAndStudentIdAndStatus(@Param("courseId") UUID courseId,
                                                   @Param("studentId") UUID studentId,
                                                   @Param("status") Enrollment.Status status);

    @Query(value = """
            select e from Enrollment e
            join fetch e.course
            left join fetch e.course.teacher
            join fetch e.student
            where e.course.teacher.id = :teacherId
            order by e.updatedAt desc
            """,
            countQuery = """
                    select count(e) from Enrollment e
                    where e.course.teacher.id = :teacherId
                    """)
    Page<Enrollment> findByTeacherId(@Param("teacherId") UUID teacherId, Pageable pageable);

    /** Предметы принятых зачислений студента (персонализация поиска, этап 5). */
    @Query("select distinct c.subject from Enrollment e join e.course c " +
            "where e.student.id = :studentId and e.status in :statuses")
    List<String> findHistorySubjectsByStudent(@Param("studentId") UUID studentId,
                                              @Param("statuses") Collection<Enrollment.Status> statuses);

    /** существует ли ACCEPTED-заявка, связывающая двух пользователей (студент↔тьютор). */
    @Query("select case when count(e) > 0 then true else false end from Enrollment e " +
            "where e.status = com.okututor.backend.enrollment.Enrollment.Status.ACCEPTED " +
            "  and ((e.student.id = :a and e.course.teacher.id = :b) " +
            "       or (e.student.id = :b and e.course.teacher.id = :a))")
    boolean existsAcceptedBetween(@Param("a") UUID a, @Param("b") UUID b);

    // ---------- статистика ----------

    long countByStatus(Enrollment.Status status);

    @Query("select count(e) from Enrollment e " +
            "where e.course.teacher.id = :teacherId and e.status = :status")
    long countByCourseTeacherIdAndStatus(@Param("teacherId") UUID teacherId,
                                         @Param("status") Enrollment.Status status);

    /** уникальные курсы, на которые студент записан (принятые заявки). */
    @Query("select count(distinct e.course.id) from Enrollment e " +
            "where e.student.id = :studentId and e.status in :statuses")
    long countDistinctCourses(@Param("studentId") UUID studentId,
                              @Param("statuses") Collection<Enrollment.Status> statuses);

    /** идемпотентный batch-перевод зависших заявок в EXPIRED (студент не ответил). */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update Enrollment e set e.status = com.okututor.backend.enrollment.Enrollment.Status.EXPIRED, "
            + "e.updatedAt = :now where e.status in :statuses and e.expiresAt is not null and e.expiresAt < :now")
    int expireStale(@Param("statuses") Collection<Enrollment.Status> statuses, @Param("now") java.time.Instant now);
}
