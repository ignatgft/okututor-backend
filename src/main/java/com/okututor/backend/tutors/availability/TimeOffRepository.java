package com.okututor.backend.tutors.availability;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TimeOffRepository extends JpaRepository<TimeOff, UUID> {
    List<TimeOff> findByTutorId(UUID tutorId);

    @Query("select toff from TimeOff toff where toff.tutor.id = :tutorId and toff.startDate <= :end and toff.endDate >= :start")
    List<TimeOff> findOverlapping(@Param("tutorId") UUID tutorId, @Param("start") LocalDate start, @Param("end") LocalDate end);
}