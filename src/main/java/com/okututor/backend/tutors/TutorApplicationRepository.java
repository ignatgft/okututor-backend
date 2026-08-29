package com.okututor.backend.tutors;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TutorApplicationRepository extends JpaRepository<TutorApplication, UUID> {

    Optional<TutorApplication> findByUserId(UUID userId);

    Page<TutorApplication> findByStatusOrderByCreatedAtDesc(TutorApplication.Status status, Pageable pageable);

    long countByStatus(TutorApplication.Status status);

    @Query("select a from TutorApplication a where (:status is null or a.status = :status) "
            + "order by a.createdAt desc")
    Page<TutorApplication> findAllOptionalStatus(@Param("status") TutorApplication.Status status, Pageable pageable);
}
