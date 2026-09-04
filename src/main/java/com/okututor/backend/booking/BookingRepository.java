package com.okututor.backend.booking;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    /**
     * SELECT ... FOR UPDATE: блокирует строку брони до коммита. Используется
     * MeetingService, чтобы сериализовать параллельные token/end-запросы для
     * одной booking — между транзакциями не может образоваться гонка
     * «оба не нашли meeting_session → оба вставили» (вторая вставка упала бы
     * с duplicate key, а PostgreSQL abort'ит транзакцию после constraint violation).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") UUID id);

    /** join fetch снимает N+1 при маппинге списка в DTO (course_title и т.п.). */
    @Query(value = """
            select b from Booking b
            join fetch b.course
            join fetch b.student
            join fetch b.teacher
            where b.student.id = :studentId
            order by b.startAt desc
            """,
            countQuery = "select count(b) from Booking b where b.student.id = :studentId")
    Page<Booking> findByStudentIdOrderByStartAtDesc(@Param("studentId") UUID studentId, Pageable pageable);

    @Query(value = """
            select b from Booking b
            join fetch b.course
            join fetch b.student
            join fetch b.teacher
            where b.teacher.id = :teacherId
            order by b.startAt desc
            """,
            countQuery = "select count(b) from Booking b where b.teacher.id = :teacherId")
    Page<Booking> findByTeacherIdOrderByStartAtDesc(@Param("teacherId") UUID teacherId, Pageable pageable);

    Optional<Booking> findByIdAndStatusIn(UUID id, Iterable<Booking.Status> statuses);

    @Query("select case when count(b) > 0 then true else false end from Booking b " +
            "where b.course.id = :courseId and b.student.id = :studentId and b.status = :status")
    boolean existsByCourseIdAndStudentIdAndStatus(@Param("courseId") UUID courseId,
                                                   @Param("studentId") UUID studentId,
                                                   @Param("status") Booking.Status status);

    /**
     * Активные будущие бронирования нескольких репетиторов одним запросом
     * (расчёт доступности в поиске, без N+1).
     */
    @Query("select b from Booking b where b.teacher.id in :teacherIds " +
            "and b.status in :statuses and b.startAt >= :from and b.startAt < :to")
    List<Booking> findActiveByTeacherIds(@Param("teacherIds") Collection<UUID> teacherIds,
                                         @Param("statuses") Collection<Booking.Status> statuses,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to);

    /** Предметы успешных бронирований студента (персонализация поиска, этап 5). */
    @Query("select distinct c.subject from Booking b join b.course c " +
            "where b.student.id = :studentId and b.status in :statuses")
    List<String> findHistorySubjectsByStudent(@Param("studentId") UUID studentId,
                                              @Param("statuses") Collection<Booking.Status> statuses);

    /** Репетиторы успешных бронирований студента (персонализация поиска, этап 5). */
    @Query("select distinct b.teacher.id from Booking b " +
            "where b.student.id = :studentId and b.status in :statuses")
    List<UUID> findHistoryTeacherIdsByStudent(@Param("studentId") UUID studentId,
                                              @Param("statuses") Collection<Booking.Status> statuses);

    // ---------- статистика (прогресс студента/тьютора) ----------
    // явные @Query: student_id/teacher_id — это ассоциации (b.student.id / b.teacher.id),
    // а не собственные атрибуты, поэтому derived-методы Spring Data их не резолвят.

    @Query("select count(b) from Booking b where b.student.id = :studentId " +
            "and b.status = :status and b.startAt >= :from")
    long countByStudentIdAndStatusAndStartAtGreaterThanEqual(@Param("studentId") UUID studentId,
                                                             @Param("status") Booking.Status status,
                                                             @Param("from") Instant from);

    @Query("select count(b) from Booking b where b.teacher.id = :teacherId and b.status = :status")
    long countByTeacherIdAndStatus(@Param("teacherId") UUID teacherId, @Param("status") Booking.Status status);

    @Query("select count(b) from Booking b where b.teacher.id = :teacherId " +
            "and b.status = :status and b.startAt >= :from")
    long countByTeacherIdAndStatusAndStartAtGreaterThanEqual(@Param("teacherId") UUID teacherId,
                                                             @Param("status") Booking.Status status,
                                                             @Param("from") Instant from);

    @Query("select coalesce(sum(b.durationMinutes), 0) from Booking b " +
            "where b.student.id = :studentId and b.status = com.okututor.backend.booking.Booking.Status.COMPLETED")
    long totalMinutesAsStudent(@Param("studentId") UUID studentId);

    @Query("select coalesce(sum(b.durationMinutes), 0) from Booking b " +
            "where b.teacher.id = :teacherId and b.status = com.okututor.backend.booking.Booking.Status.COMPLETED")
    long totalMinutesAsTeacher(@Param("teacherId") UUID teacherId);

    /** завершённые брони студента (для сводки по месяцам). */
    @Query("select b from Booking b where b.student.id = :studentId " +
            "and b.status = com.okututor.backend.booking.Booking.Status.COMPLETED")
    List<Booking> completedForStudent(@Param("studentId") UUID studentId);

    /** кол-во уникальных студентов тьютора по живым/завершённым занятиям. */
    @Query("select count(distinct b.student.id) from Booking b " +
            "where b.teacher.id = :teacherId and b.status in :statuses")
    long countDistinctStudents(@Param("teacherId") UUID teacherId,
                               @Param("statuses") Collection<Booking.Status> statuses);

    /** завершённые брони тьютора (для расчёта часов/списка). */
    @Query("select b from Booking b where b.teacher.id = :teacherId " +
            "and b.status = com.okututor.backend.booking.Booking.Status.COMPLETED")
    List<Booking> completedForTeacher(@Param("teacherId") UUID teacherId);

    // ---------- календарь ----------
    // join fetch course/student/teacher снимают N+1 при маппинге CalendarItem
    // (course_title, counterpart, location_type и т.п.).

    @Query("""
            select b from Booking b
            join fetch b.course c
            join fetch b.student s
            join fetch b.teacher t
            where b.student.id = :userId and b.startAt >= :from and b.startAt < :to
            order by b.startAt asc
            """)
    List<Booking> calendarByStudent(@Param("userId") UUID userId,
                                    @Param("from") Instant from,
                                    @Param("to") Instant to);

    @Query("""
            select b from Booking b
            join fetch b.course c
            join fetch b.student s
            join fetch b.teacher t
            where b.teacher.id = :userId and b.startAt >= :from and b.startAt < :to
            order by b.startAt asc
            """)
    List<Booking> calendarByTeacher(@Param("userId") UUID userId,
                                    @Param("from") Instant from,
                                    @Param("to") Instant to);

    @Query("""
            select b from Booking b
            join fetch b.course c
            join fetch b.student s
            join fetch b.teacher t
            where b.startAt >= :from and b.startAt < :to
            order by b.startAt asc
            """)
    List<Booking> calendarAll(@Param("from") Instant from, @Param("to") Instant to);

    // ---------- серия занятий: конфликт пересекающихся интервалов ----------
    // Пересечение по интервалу, а не только по совпадающему start_at:
    // 10:00–11:00 и 11:00–12:00 НЕ конфликт, а 10:00–11:00 и 10:59–11:30 — конфликт.
    // statuses — «живые» брони (PENDING/CONFIRMED).

    @Query("select case when count(b) > 0 then true else false end from Booking b " +
            "where b.student.id = :userId and b.status in :statuses " +
            "and b.startAt < :to and b.endAt > :from")
    boolean overlapsStudent(@Param("userId") UUID userId,
                            @Param("statuses") Collection<Booking.Status> statuses,
                            @Param("from") Instant from,
                            @Param("to") Instant to);

    /** как {@link #overlapsStudent}, но не считает бронь с указанным id (перенос той же брони). */
    @Query("select case when count(b) > 0 then true else false end from Booking b " +
            "where b.status in :statuses and b.id <> :excludeId " +
            "and (b.student.id = :userId or b.teacher.id = :userId) " +
            "and b.startAt < :to and b.endAt > :from")
    boolean overlapsParticipantExcluding(@Param("userId") UUID userId,
                                         @Param("excludeId") UUID excludeId,
                                         @Param("statuses") Collection<Booking.Status> statuses,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to);

    @Query("select case when count(b) > 0 then true else false end from Booking b " +
            "where b.teacher.id = :userId and b.status in :statuses " +
            "and b.startAt < :to and b.endAt > :from")
    boolean overlapsTeacher(@Param("userId") UUID userId,
                            @Param("statuses") Collection<Booking.Status> statuses,
                            @Param("from") Instant from,
                            @Param("to") Instant to);

    @Query("""
            select b from Booking b
            where b.teacher.id = :teacherId
            and b.status in :statuses
            and b.startAt >= :from and b.startAt < :to
            order by b.startAt asc
            """)
    List<Booking> findByTeacherIdAndStartAtBetween(@Param("teacherId") UUID teacherId,
                                                    @Param("statuses") Collection<Booking.Status> statuses,
                                                    @Param("from") Instant from,
                                                    @Param("to") Instant to);

    // ---------- расписания ----------

    boolean existsByScheduleId(UUID scheduleId);

    @Query("select b from Booking b where b.schedule.id = :scheduleId and b.status in :statuses order by b.startAt asc")
    List<Booking> findByScheduleIdAndStatusIn(@Param("scheduleId") UUID scheduleId,
                                               @Param("statuses") Collection<Booking.Status> statuses);

    List<Booking> findByScheduleIdOrderByStartAtAsc(UUID scheduleId);

    // ---------- admin metrics ----------

    @Query("select b.status, count(b) from Booking b group by b.status")
    List<Object[]> countGroupByStatus();

    long countByCreatedAtAfter(Instant from);

    /** выручка = сумма (длительность_часов * цена_курса) по завершённым броням. */
    @Query("select coalesce(sum(b.durationMinutes * b.course.pricePerHour / 60.0), 0) " +
            "from Booking b where b.status = com.okututor.backend.booking.Booking.Status.COMPLETED")
    java.math.BigDecimal revenueCompleted();

    @Query("select coalesce(sum(b.durationMinutes * b.course.pricePerHour / 60.0), 0) " +
            "from Booking b where b.status = com.okututor.backend.booking.Booking.Status.COMPLETED " +
            "and b.createdAt >= :from")
    java.math.BigDecimal revenueCompletedSince(@Param("from") Instant from);
}
