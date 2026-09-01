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
        validateBookingState(booking);
        validateTimeWindow(booking);

        Instant now = Instant.now();
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
        Booking booking = requireParticipantBookingLocked(bookingId, requester);
        validateBookingState(booking);
        // не создаём сессию если join не был выполнен — это логически некорректная сущность (startedAt=null)
        MeetingSession session = meetingRepository.findByBookingId(bookingId)
                .orElseThrow(() -> ApiException.notFound(
                        com.okututor.backend.common.error.ErrorCodes.NOT_FOUND,
                        "No meeting session found for this booking - join the lesson first"));
        if (session.getStartedAt() == null) {
            throw ApiException.conflict(com.okututor.backend.common.error.ErrorCodes.MEETING_NOT_AVAILABLE,
                    "Meeting was never started - cannot end a session that was not joined");
        }
        // идемпотентно: повторный end не меняет состояние кроме обновления endedAt если уже завершено
        Instant now = Instant.now();
        session.setEndedAt(now);
        meetingRepository.save(session);
        return java.util.Map.of("status", "ENDED");
    }

    private void validateBookingState(Booking booking) {
        if (booking.getStatus() != Booking.Status.CONFIRMED && booking.getStatus() != Booking.Status.COMPLETED) {
            throw ApiException.conflict("Lesson is available after the tutor confirms the booking");
        }
    }

    private void validateTimeWindow(Booking booking) {
        var window = properties.getLesson();
        Instant now = Instant.now();
        Instant opensAt = booking.getStartAt().minusSeconds(window.getJoinMinutesBefore() * 60L);
        Instant closesAt = booking.getEndAt().plusSeconds(window.getJoinMinutesAfter() * 60L);
        if (now.isBefore(opensAt) || now.isAfter(closesAt)) {
            throw ApiException.forbidden(com.okututor.backend.common.error.ErrorCodes.MEETING_NOT_AVAILABLE,
                    "The lesson room is open from %s and until %s (UTC)"
                            .formatted(opensAt, closesAt));
        }
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