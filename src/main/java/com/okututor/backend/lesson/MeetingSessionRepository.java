package com.okututor.backend.lesson;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingSessionRepository extends JpaRepository<MeetingSession, UUID> {

    Optional<MeetingSession> findByBookingId(UUID bookingId);

    @Query(value = """
        select case when count(ms.id) > 0 then true else false end
        from meeting_sessions ms
        join bookings b on b.id = ms.booking_id
        where b.course_id = :courseId
          and b.student_id = :studentId
          and ms.started_at is not null
        """, nativeQuery = true)
    boolean hasAttendedLesson(@Param("courseId") UUID courseId, @Param("studentId") UUID studentId);
}
