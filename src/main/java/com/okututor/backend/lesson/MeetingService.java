package com.okututor.backend.lesson;

import com.okututor.backend.booking.Booking;
import com.okututor.backend.booking.BookingService;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.user.User;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeetingService {

    private final BookingService bookingService;
    private final MeetingSessionRepository meetingRepository;
    private final LiveKitTokenService liveKitTokenService;
    private final com.okututor.backend.common.config.AppProperties properties;

    public MeetingService(BookingService bookingService,
                          MeetingSessionRepository meetingRepository,
                          LiveKitTokenService liveKitTokenService,
                          com.okututor.backend.common.config.AppProperties properties) {
        this.bookingService = bookingService;
        this.meetingRepository = meetingRepository;
        this.liveKitTokenService = liveKitTokenService;
        this.properties = properties;
    }

    /**
     * только участники брони могут получить join-токен; статус CONFIRMED/COMPLETED;
     * вход ограничен временным окном вокруг расписания (UTC):
     * [startAt - N минут, endAt + M минут], настраивается в app.lesson.*.
     * (админам тоже отказано намеренно — комната урока приватная)
     */
    @Transactional
    public LiveKitTokenService.MeetingToken token(User requester, UUID bookingId) {
        Booking booking = requireParticipantBooking(bookingId, requester);
        if (booking.getStatus() != Booking.Status.CONFIRMED && booking.getStatus() != Booking.Status.COMPLETED) {
            throw ApiException.conflict("Lesson is available after the tutor confirms the booking");
        }

        var window = properties.getLesson();
        Instant now = Instant.now();
        Instant opensAt = booking.getStartAt().minusSeconds(window.getJoinMinutesBefore() * 60L);
        Instant closesAt = booking.getEndAt().plusSeconds(window.getJoinMinutesAfter() * 60L);
        if (now.isBefore(opensAt) || now.isAfter(closesAt)) {
            throw ApiException.forbidden(com.okututor.backend.common.error.ErrorCodes.MEETING_NOT_AVAILABLE,
                    "The lesson room is open from %s and until %s (UTC)"
                            .formatted(opensAt, closesAt));
        }

        MeetingSession session = meetingRepository.findByBookingId(bookingId).orElseGet(() -> {
            MeetingSession fresh = new MeetingSession();
            fresh.setBookingId(bookingId);
            fresh.setRoomName(LiveKitTokenService.roomName(bookingId));
            fresh.setStartedAt(Instant.now());
            return fresh;
        });
        session.setTokenIssuedAt(Instant.now());
        if (session.getEndedAt() != null) {
            session.setEndedAt(null); // разрешаем повторный вход в течение TTL после случайного выхода
        }
        session = meetingRepository.save(session);

        return liveKitTokenService.issue(bookingId, requester.getId(),
                requester.getFullName());
    }

    /** Вызывается PgLesson при выходе из урока; ошибки фронт глотает сам. */
    @Transactional
    public java.util.Map<String, String> end(User requester, UUID bookingId) {
        requireParticipantBooking(bookingId, requester);
        MeetingSession session = meetingRepository.findByBookingId(bookingId).orElseGet(() -> {
            MeetingSession fresh = new MeetingSession();
            fresh.setBookingId(bookingId);
            fresh.setRoomName(LiveKitTokenService.roomName(bookingId));
            return fresh;
        });
        session.setEndedAt(Instant.now());
        meetingRepository.save(session);
        return java.util.Map.of("status", "ENDED");
    }

    private Booking requireParticipantBooking(UUID bookingId, User requester) {
        Booking booking = bookingService.requireById(bookingId);
        if (!booking.involves(requester.getId())) {
            throw ApiException.forbidden("Only booking participants can access the meeting");
        }
        return booking;
    }
}
