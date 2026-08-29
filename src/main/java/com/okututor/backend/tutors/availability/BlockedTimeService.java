package com.okututor.backend.tutors.availability;

import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.common.error.FieldValidationException;
import com.okututor.backend.tutors.availability.dto.BlockedTimeRequest;
import com.okututor.backend.tutors.availability.dto.BlockedTimeResponse;
import com.okututor.backend.user.User;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BlockedTimeService {

    private final BlockedTimeRepository repository;

    public BlockedTimeService(BlockedTimeRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<BlockedTimeResponse> listForTutor(UUID tutorId) {
        return repository.findByTutorId(tutorId).stream()
                .map(this::toResponse)
                .toList();
    }

    public BlockedTimeResponse add(User tutor, BlockedTimeRequest req) {
        if (req.startAt() == null || req.endAt() == null || !req.endAt().isAfter(req.startAt())) {
            throw new FieldValidationException(java.util.Map.of("endAt", "endAt must be after startAt"));
        }

        BlockedTime blocked = new BlockedTime();
        blocked.setTutor(tutor);
        blocked.setStartAt(req.startAt());
        blocked.setEndAt(req.endAt());
        blocked.setReason(req.reason());
        return toResponse(repository.save(blocked));
    }

    public void remove(User tutor, UUID blockedId) {
        BlockedTime blocked = repository.findById(blockedId)
                .filter(b -> b.getTutor().getId().equals(tutor.getId()))
                .orElseThrow(() -> ApiException.notFound("Blocked time not found"));
        repository.delete(blocked);
    }

    private BlockedTimeResponse toResponse(BlockedTime b) {
        return new BlockedTimeResponse(b.getId(), b.getStartAt(), b.getEndAt(), b.getReason());
    }
}