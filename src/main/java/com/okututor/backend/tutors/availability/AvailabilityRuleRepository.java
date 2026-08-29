package com.okututor.backend.tutors.availability;

import com.okututor.backend.user.User;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AvailabilityRuleRepository extends JpaRepository<AvailabilityRule, UUID> {
    List<AvailabilityRule> findByTutorIdOrderByWeekdayAscStartTimeAsc(UUID tutorId);

    @Query("select ar from AvailabilityRule ar where ar.tutor.id = :tutorId and ar.weekday = :weekday")
    List<AvailabilityRule> findByTutorIdAndWeekday(@Param("tutorId") UUID tutorId, @Param("weekday") String weekday);
}