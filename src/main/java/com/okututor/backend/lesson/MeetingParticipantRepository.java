package com.okututor.backend.lesson;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingParticipantRepository extends JpaRepository<MeetingParticipant, UUID> {

    Optional<MeetingParticipant> findByMeetingSessionIdAndIdentity(UUID meetingSessionId, String identity);
}
