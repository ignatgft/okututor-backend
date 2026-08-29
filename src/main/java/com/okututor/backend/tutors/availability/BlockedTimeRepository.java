package com.okututor.backend.tutors.availability;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlockedTimeRepository extends JpaRepository<BlockedTime, UUID> {
    List<BlockedTime> findByTutorId(UUID tutorId);

    @Query("select bt from BlockedTime bt where bt.tutor.id = :tutorId and bt.startAt < :end and bt.endAt > :start")
    List<BlockedTime> findOverlapping(@Param("tutorId") UUID tutorId, @Param("start") Instant start, @Param("end") Instant end);
}