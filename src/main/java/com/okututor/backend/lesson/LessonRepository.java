package com.okututor.backend.lesson;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LessonRepository extends JpaRepository<Lesson, UUID> {

    /** join fetch участников: LessonResponse читает full_name без N+1. */
    @Query(value = """
            select l from Lesson l
            join fetch l.teacher
            join fetch l.student
            left join fetch l.course
            where l.student.id = :studentId
            """,
            countQuery = "select count(l) from Lesson l where l.student.id = :studentId")
    Page<Lesson> findByStudentId(@Param("studentId") UUID studentId, Pageable pageable);

    @Query(value = """
            select l from Lesson l
            join fetch l.teacher
            join fetch l.student
            left join fetch l.course
            where l.teacher.id = :teacherId
            """,
            countQuery = "select count(l) from Lesson l where l.teacher.id = :teacherId")
    Page<Lesson> findByTeacherId(@Param("teacherId") UUID teacherId, Pageable pageable);

    Optional<Lesson> findByBookingId(UUID bookingId);

    @Query("select l.status from Lesson l where l.booking.id = :bookingId")
    Optional<String> statusByBooking(@Param("bookingId") UUID bookingId);

    // ---------- календарь ----------
    // Только «самостоятельные» уроки (без привязки к брони) — брони уже попадают
    // в календарь из bookings; join fetch снимает N+1 при маппинге CalendarItem.

    @Query("""
            select l from Lesson l
            join fetch l.teacher t
            join fetch l.student s
            left join fetch l.course c
            where l.booking is null and l.student.id = :userId
              and l.startAt >= :from and l.startAt < :to
            order by l.startAt asc
            """)
    List<Lesson> calendarByStudent(@Param("userId") UUID userId,
                                   @Param("from") java.time.Instant from,
                                   @Param("to") java.time.Instant to);

    @Query("""
            select l from Lesson l
            join fetch l.teacher t
            join fetch l.student s
            left join fetch l.course c
            where l.booking is null and l.teacher.id = :userId
              and l.startAt >= :from and l.startAt < :to
            order by l.startAt asc
            """)
    List<Lesson> calendarByTeacher(@Param("userId") UUID userId,
                                   @Param("from") java.time.Instant from,
                                   @Param("to") java.time.Instant to);

    @Query("""
            select l from Lesson l
            join fetch l.teacher t
            join fetch l.student s
            left join fetch l.course c
            where l.booking is null and l.startAt >= :from and l.startAt < :to
            order by l.startAt asc
            """)
    List<Lesson> calendarAll(@Param("from") java.time.Instant from,
                             @Param("to") java.time.Instant to);

    // ---------- расписания / конфликты / напоминания ----------

    /** конфликт «самостоятельных» уроков с интервалом (для проверки перед созданием/переносом). */
    @Query("select case when count(l) > 0 then true else false end from Lesson l " +
            "where l.status in :statuses and l.startAt < :to and l.endAt > :from " +
            "and (l.student.id = :userId or l.teacher.id = :userId)")
    boolean overlapsParticipant(@Param("userId") UUID userId,
                                @Param("statuses") java.util.Collection<Lesson.Status> statuses,
                                @Param("from") java.time.Instant from,
                                @Param("to") java.time.Instant to);

    /** как {@link #overlapsParticipant}, но исключает сам переносимый урок. */
    @Query("select case when count(l) > 0 then true else false end from Lesson l " +
            "where l.status in :statuses and l.id <> :excludeId " +
            "and (l.student.id = :userId or l.teacher.id = :userId) " +
            "and l.startAt < :to and (l.endAt is not null and l.endAt > :from)")
    boolean overlapsParticipantExcluding(@Param("userId") UUID userId,
                                         @Param("excludeId") UUID excludeId,
                                         @Param("statuses") java.util.Collection<Lesson.Status> statuses,
                                         @Param("from") java.time.Instant from,
                                         @Param("to") java.time.Instant to);

    java.util.List<Lesson> findByScheduleIdOrderByStartAtAsc(UUID scheduleId);

    boolean existsByScheduleId(UUID scheduleId);

    @Query("select l from Lesson l where l.status = :status and l.startAt >= :from and l.startAt <= :to order by l.startAt asc")
    java.util.List<Lesson> findUpcomingForReminder(@Param("status") Lesson.Status status,
                                                   @Param("from") java.time.Instant from,
                                                   @Param("to") java.time.Instant to);
}
