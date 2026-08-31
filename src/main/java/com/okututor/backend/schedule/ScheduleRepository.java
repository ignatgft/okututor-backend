package com.okututor.backend.schedule;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {

    Optional<Schedule> findByApplicationId(UUID applicationId);

    @EntityGraph(attributePaths = {"application", "course", "student", "tutor", "slots"})
    Optional<Schedule> findDetailedById(UUID id);

    @EntityGraph(attributePaths = {"application", "course", "student", "tutor", "slots"})
    @Query("select s from Schedule s where s.student.id = :studentId order by s.updatedAt desc")
    List<Schedule> findByStudentIdOrderByUpdatedAtDesc(@Param("studentId") UUID studentId);

    @EntityGraph(attributePaths = {"application", "course", "student", "tutor", "slots"})
    @Query("select s from Schedule s where s.tutor.id = :tutorId order by s.updatedAt desc")
    List<Schedule> findByTutorIdOrderByUpdatedAtDesc(@Param("tutorId") UUID tutorId);

    long countByApplicationId(UUID applicationId);

    @Query("select count(s) > 0 from Schedule s where s.application.id = :applicationId and s.status = :status")
    boolean existsByApplicationIdAndStatus(@Param("applicationId") UUID applicationId,
                                           @Param("status") Schedule.Status status);
}