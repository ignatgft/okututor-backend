package com.okututor.backend.tutors.availability;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AvailabilityExceptionRepository extends JpaRepository<AvailabilityException, UUID> {
    List<AvailabilityException> findByTutorId(UUID tutorId);

    @Query("select ae from AvailabilityException ae where ae.tutor.id = :tutorId and ae.exceptionDate = :date")
    Optional<AvailabilityException> findByTutorIdAndExceptionDate(@Param("tutorId") UUID tutorId, @Param("date") LocalDate date);
}