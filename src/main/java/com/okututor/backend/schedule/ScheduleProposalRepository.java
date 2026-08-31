package com.okututor.backend.schedule;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleProposalRepository extends JpaRepository<ScheduleProposal, UUID> {

    java.util.List<ScheduleProposal> findByApplicationIdOrderByCreatedAtDesc(UUID applicationId);

    Optional<ScheduleProposal> findFirstByApplicationIdAndStatusOrderByCreatedAtDesc(
            UUID applicationId, ScheduleProposal.Status status);

    java.util.List<ScheduleProposal> findByApplicationIdAndStatus(UUID applicationId, ScheduleProposal.Status status);

    boolean existsByApplicationIdAndStatus(UUID applicationId, ScheduleProposal.Status status);
}