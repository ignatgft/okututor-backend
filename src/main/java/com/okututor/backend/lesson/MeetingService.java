package com.okututor.backend.lesson;

import com.okututor.backend.booking.Booking;
import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.common.error.ApiException;
import com.okututor.backend.user.User;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeetingService {

    private final BookingRepository bookingRepository;
    private final MeetingSessionRepository meetingRepository;
    private final LiveKitTokenService liveKitTokenService;
    private final com.okututor.backend.common.config.AppProperties properties;

    public MeetingService(BookingRepository bookingRepository,
                          MeetingSessionRepository meetingRepository,
                          LiveKitTokenService liveKitTokenService,
                          com.okututor.backend.common.config.AppProperties properties) {
        this.bookingRepository = bookingRepository;
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
        // SELECT ... FOR UPDATE: параллельные запросы на один booking сериализуются,
        // гонка find-then-insert на meeting_sessions исключена на уровне БД
        Booking booking = requireParticipantBookingLocked(bookingId, requester);
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

        MeetingSession session = findOrCreateSession(bookingId, now);
        // startedAt фиксируется при первом входе и не перезаписывается при повторах
        if (session.getStartedAt() == null) {
            session.setStartedAt(now);
        }
        session.setTokenIssuedAt(now);
        if (session.getEndedAt() != null) {
            session.setEndedAt(null); // разрешаем повторный вход в течение TTL после случайного выхода
        }
        meetingRepository.save(session);

        return liveKitTokenService.issue(bookingId, requester.getId(),
                requester.getFullName());
    }

    /** Вызывается PgLesson при выходе из урока; ошибки фронт глотает сам. */
    @Transactional
    public java.util.Map<String, String> end(User requester, UUID bookingId) {
        requireParticipantBookingLocked(bookingId, requester);
        MeetingSession session = findOrCreateSession(bookingId, Instant.now());
        session.setEndedAt(Instant.now());
        meetingRepository.save(session);
        return java.util.Map.of("status", "ENDED");
    }

    /**
     * Одна booking → одна MeetingSession (UNIQUE booking_id в БД). Родительский
     * транзакционный метод блокирует строку booking (SELECT ... FOR UPDATE),
     * поэтому сюда два потока одновременно не входят: второй ждёт коммита первого
     * и находит уже вставленную запись. Вставка идёт без catch-then-reread —
     * в PostgreSQL после constraint violation транзакция обрывается и повторное
     * чтение внутри неё всё равно упадёт.
     */
    private MeetingSession findOrCreateSession(UUID bookingId, Instant now) {
        Optional<MeetingSession> existing = meetingRepository.findByBookingId(bookingId);
        if (existing.isPresent()) {
            return existing.get();
        }
        MeetingSession fresh = new MeetingSession();
        fresh.setBookingId(bookingId);
        fresh.setRoomName(LiveKitTokenService.roomName(bookingId));
        fresh.setStartedAt(now);
        return meetingRepository.save(fresh);
    }

    private Booking requireParticipantBookingLocked(UUID bookingId, User requester) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> ApiException.notFound("Booking not found"));
        if (!booking.involves(requester.getId())) {
            throw ApiException.forbidden("Only booking participants can access the meeting");
        }
        return booking;
    }
}