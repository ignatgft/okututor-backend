package com.okututor.backend.tutors;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AvailabilitySlotRepository extends JpaRepository<AvailabilitySlot, UUID> {

    List<AvailabilitySlot> findByTutorIdOrderByWeekdayAscStartTimeAsc(UUID tutorId);

    Optional<AvailabilitySlot> findByIdAndTutorId(UUID id, UUID tutorId);

    List<AvailabilitySlot> findByTutorIdAndWeekday(UUID tutorId, String weekday);

    /** Batch-загрузка слотов нескольких репетиторов (поиск без N+1). */
    List<AvailabilitySlot> findByTutorIdIn(Collection<UUID> tutorIds);
}
