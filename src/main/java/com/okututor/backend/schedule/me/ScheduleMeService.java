package com.okututor.backend.schedule.me;

import com.okututor.backend.booking.Booking;
import com.okututor.backend.booking.BookingProposal;
import com.okututor.backend.booking.BookingProposalRepository;
import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.enrollment.Enrollment;
import com.okututor.backend.enrollment.EnrollmentRepository;
import com.okututor.backend.lesson.Lesson;
import com.okututor.backend.lesson.LessonMapper;
import com.okututor.backend.lesson.LessonRepository;
import com.okututor.backend.lesson.MeetingSessionRepository;
import com.okututor.backend.lesson.dto.LessonDTO;
import com.okututor.backend.lesson.dto.LessonStatusLabelService;
import com.okututor.backend.review.ReviewRepository;
import com.okututor.backend.schedule.ScheduleProposal;
import com.okututor.backend.schedule.ScheduleProposalRepository;
import com.okututor.backend.schedule.me.dto.ScheduleActionDto;
import com.okututor.backend.schedule.me.dto.ScheduleMeDtos;
import com.okututor.backend.user.User;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ScheduleMeService {

    private final LessonRepository lessonRepository;
    private final LessonMapper lessonMapper;
    private final LessonStatusLabelService labelService;
    private final ScheduleProposalRepository scheduleProposalRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final BookingRepository bookingRepository;
    private final BookingProposalRepository bookingProposalRepository;
    private final MeetingSessionRepository meetingSessionRepository;
    private final ReviewRepository reviewRepository;

    public ScheduleMeService(LessonRepository lessonRepository,
                             LessonMapper lessonMapper,
                             LessonStatusLabelService labelService,
                             ScheduleProposalRepository scheduleProposalRepository,
                             EnrollmentRepository enrollmentRepository,
                             BookingRepository bookingRepository,
                             BookingProposalRepository bookingProposalRepository,
                             MeetingSessionRepository meetingSessionRepository,
                             ReviewRepository reviewRepository) {
        this.lessonRepository = lessonRepository;
        this.lessonMapper = lessonMapper;
        this.labelService = labelService;
        this.scheduleProposalRepository = scheduleProposalRepository;
        this.enrollmentRepository = enrollmentRepository;
        this.bookingRepository = bookingRepository;
        this.bookingProposalRepository = bookingProposalRepository;
        this.meetingSessionRepository = meetingSessionRepository;
        this.reviewRepository = reviewRepository;
    }

    @Transactional(readOnly = true)
    public ScheduleMeDtos.NextLessonResponse getNext(User user, ZoneId zone, Locale locale) {
        Instant now = Instant.now();
        // 1. ongoing IN_PROGRESS lesson (started but not ended)
        List<Lesson> all = lessonRepository.findForUserBetween(user.getId(),
                now.minusSeconds(4 * 3600), now.plusSeconds(30L * 24 * 3600));
        // filter ongoing first
        Optional<Lesson> ongoing = all.stream()
                .filter(l -> l.getStatus() == Lesson.Status.IN_PROGRESS)
                .filter(l -> l.getStartAt() != null && l.getEndAt() != null
                        && !now.isBefore(l.getStartAt()) && now.isBefore(l.getEndAt()))
                .sorted(Comparator.comparing(Lesson::getStartAt))
                .findFirst();
        Lesson next;
        if (ongoing.isPresent()) {
            next = ongoing.get();
        } else {
            // upcoming SCHEDULED / IN_PROGRESS with start >= now
            List<Lesson> candidates = lessonRepository.findNextCandidates(
                    user.getId(),
                    List.of(Lesson.Status.SCHEDULED, Lesson.Status.IN_PROGRESS),
                    now,
                    PageRequest.of(0, 1));
            if (candidates.isEmpty()) {
                return new ScheduleMeDtos.NextLessonResponse(null, null, false, null);
            }
            next = candidates.get(0);
        }
        LessonDTO dto = lessonMapper.toDTO(next, user.getId(), locale, now);
        long countdown = 0;
        if (next.getStartAt() != null) {
            countdown = Duration.between(now, next.getStartAt()).getSeconds();
            if (countdown < 0) countdown = 0;
        }
        return new ScheduleMeDtos.NextLessonResponse(dto, countdown, dto.canJoin(), dto.meetingRoomId());
    }

    @Transactional(readOnly = true)
    public ScheduleMeDtos.DayResponse getDay(User user, LocalDate date, ZoneId zone, Locale locale) {
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<Lesson> lessons = lessonRepository.findForUserBetween(user.getId(), from, to);
        Instant now = Instant.now();
        List<LessonDTO> dtos = lessons.stream()
                .map(l -> lessonMapper.toDTO(l, user.getId(), locale, now))
                .sorted(Comparator.comparing(LessonDTO::startAt))
                .toList();
        return new ScheduleMeDtos.DayResponse(date.toString(), dtos, dtos.size());
    }

    @Transactional(readOnly = true)
    public ScheduleMeDtos.WeekResponse getWeek(User user, LocalDate startDate, ZoneId zone, Locale locale) {
        LocalDate endDate = startDate.plusDays(6);
        Instant from = startDate.atStartOfDay(zone).toInstant();
        Instant to = endDate.plusDays(1).atStartOfDay(zone).toInstant();
        List<Lesson> lessons = lessonRepository.findForUserBetween(user.getId(), from, to);
        Instant now = Instant.now();
        Map<LocalDate, List<Lesson>> byDate = lessons.stream()
                .collect(Collectors.groupingBy(l -> l.getStartAt() != null ? l.getStartAt().atZone(zone).toLocalDate() : startDate,
                        TreeMap::new, Collectors.toList()));

        List<ScheduleMeDtos.WeekDayGroup> days = new ArrayList<>();
        LocalDate cur = startDate;
        int total = 0;
        while (!cur.isAfter(endDate)) {
            List<Lesson> dayLessons = byDate.getOrDefault(cur, List.of());
            List<LessonDTO> dtos = dayLessons.stream()
                    .map(l -> lessonMapper.toDTO(l, user.getId(), locale, now))
                    .sorted(Comparator.comparing(LessonDTO::startAt))
                    .toList();
            days.add(new ScheduleMeDtos.WeekDayGroup(cur.toString(), dtos, dtos.size()));
            total += dtos.size();
            cur = cur.plusDays(1);
        }
        return new ScheduleMeDtos.WeekResponse(startDate.toString(), endDate.toString(), days, total);
    }

    @Transactional(readOnly = true)
    public ScheduleMeDtos.MonthResponse getMonth(User user, int year, int month, ZoneId zone, Locale locale) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate endExclusive = ym.plusMonths(1).atDay(1);
        Instant from = start.atStartOfDay(zone).toInstant();
        Instant to = endExclusive.atStartOfDay(zone).toInstant();
        List<Lesson> lessons = lessonRepository.findForUserBetween(user.getId(), from, to);
        // group by date
        Map<LocalDate, List<Lesson>> byDate = lessons.stream()
                .collect(Collectors.groupingBy(l -> l.getStartAt() != null ? l.getStartAt().atZone(zone).toLocalDate() : start,
                        TreeMap::new, Collectors.toList()));

        List<ScheduleMeDtos.MonthDaySummary> days = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Lesson>> e : byDate.entrySet()) {
            List<String> statuses = e.getValue().stream()
                    .map(l -> l.getStatus() != null ? l.getStatus().name() : "SCHEDULED")
                    .distinct()
                    .toList();
            days.add(new ScheduleMeDtos.MonthDaySummary(e.getKey().toString(), e.getValue().size(), statuses));
        }
        // также включаем даты без уроков? spec говорит компактные данные (дата+кол-во+статусы) — только дни с уроками
        return new ScheduleMeDtos.MonthResponse(year, month, days, lessons.size());
    }

    @Transactional(readOnly = true)
    public ScheduleMeDtos.ActionsResponse getActions(User user, Locale locale) {
        Instant now = Instant.now();
        List<ScheduleActionDto> actions = new ArrayList<>();

        // 1. SELECT_TIME / CONFIRM_SCHEDULE — pending schedule proposals где viewer — студент, создан тьютором
        // Запрос активных заявок пользователя
        List<Enrollment> enrollments = new ArrayList<>();
        // student side
        if (user.getRole() == com.okututor.backend.user.Role.STUDENT) {
            enrollments.addAll(enrollmentRepository.findByStudentIdOrderByUpdatedAtDesc(user.getId(), PageRequest.of(0, 100)).getContent());
        } else {
            // tutor side тоже может иметь действия (CONFIRM_RESCHEDULE)
            enrollments.addAll(enrollmentRepository.findByTeacherId(user.getId(), PageRequest.of(0, 100)).getContent());
        }
        for (Enrollment en : enrollments) {
            List<ScheduleProposal> pending = scheduleProposalRepository.findByApplicationIdAndStatus(en.getId(), ScheduleProposal.Status.PENDING);
            for (ScheduleProposal p : pending) {
                boolean isStudent = en.getStudent() != null && en.getStudent().getId().equals(user.getId());
                boolean createdByOther = p.getCreatedBy() != null && !p.getCreatedBy().getId().equals(user.getId());
                if (isStudent && createdByOther) {
                    String courseTitle = en.getCourse() != null ? en.getCourse().getTitle() : "Курс";
                    var slotDtos = p.getSlots().stream()
                            .map(s -> Map.of("weekday", s.getWeekday().name(),
                                    "startTime", s.getStartTime().toString(),
                                    "endTime", s.getEndTime().toString()))
                            .toList();
                    actions.add(new ScheduleActionDto(
                            "sched_prop_" + p.getId(),
                            "SELECT_TIME",
                            "Согласовать расписание",
                            "Тьютор предложил " + p.getSlots().size() + " времени для курса «" + courseTitle + "»",
                            "high",
                            null,
                            en.getId(),
                            new ScheduleActionDto.ActionRef("Выбрать время", "GET", "/api/v1/schedule/proposals/" + p.getId()),
                            new ScheduleActionDto.ActionRef("Написать тьютору", "GET", "/api/v1/messages/conversations/" + (en.getCourse() != null && en.getCourse().getTeacher() != null ? en.getCourse().getTeacher().getId() : "")),
                            Map.of("slots", slotDtos, "timezone", p.getTimezone(), "courseTitle", courseTitle)
                    ));
                } else if (!isStudent && createdByOther) {
                    // tutor sees counter proposal from student
                    String courseTitle = en.getCourse() != null ? en.getCourse().getTitle() : "Курс";
                    var slotDtos = p.getSlots().stream()
                            .map(s -> Map.of("weekday", s.getWeekday().name(),
                                    "startTime", s.getStartTime().toString(),
                                    "endTime", s.getEndTime().toString()))
                            .toList();
                    actions.add(new ScheduleActionDto(
                            "sched_counter_" + p.getId(),
                            "RESPOND_TO_PROPOSAL",
                            "Ответить на предложение",
                            "Студент предложил встречный вариант расписания по курсу «" + courseTitle + "»",
                            "high",
                            null,
                            en.getId(),
                            new ScheduleActionDto.ActionRef("Посмотреть предложение", "GET", "/api/v1/schedule/proposals/" + p.getId()),
                            null,
                            Map.of("slots", slotDtos, "courseTitle", courseTitle)
                    ));
                }
            }
            // Enrollment pending info request
            if (en.getStatus() == Enrollment.Status.NEEDS_INFO && en.getStudent() != null && en.getStudent().getId().equals(user.getId())) {
                actions.add(new ScheduleActionDto(
                        "enroll_info_" + en.getId(),
                        "CONFIRM_SCHEDULE",
                        "Требуется уточнение заявки",
                        "Тьютор запросил дополнительную информацию по курсу «" + (en.getCourse()!=null?en.getCourse().getTitle():"") + "»",
                        "medium",
                        null,
                        en.getId(),
                        new ScheduleActionDto.ActionRef("Уточнить заявку", "POST", "/api/v1/enrollments/" + en.getId() + "/submit-info"),
                        null,
                        Map.of("enrollmentId", en.getId())
                ));
            }
        }

        // 2. Booking proposals pending (reschedule)
        List<Lesson> userLessons = lessonRepository.findForUserBetween(user.getId(),
                now.minusSeconds(7 * 24 * 3600), now.plusSeconds(60L*24*3600));
        // Collect bookingIds from lessons
        Set<UUID> bookingIds = userLessons.stream()
                .filter(l -> l.getBooking() != null)
                .map(l -> l.getBooking().getId())
                .collect(Collectors.toSet());
        // Also check recent bookings via bookingRepository (student/teacher)
        // For simplicity, query all active proposals for user's bookings via bookingProposalRepository
        // We'll fetch all pending proposals where booking involves user
        for (UUID bid : bookingIds) {
            List<BookingProposal> active = bookingProposalRepository.findActiveProposalsForBooking(bid, now);
            for (BookingProposal bp : active) {
                if (bp.getProposedBy() == null || bp.getProposedBy().getId().equals(user.getId())) continue;
                Booking booking = bp.getBooking();
                String courseTitle = booking != null && booking.getCourse() != null ? booking.getCourse().getTitle() : "Занятие";
                Lesson relatedLesson = userLessons.stream().filter(l-> l.getBooking()!=null && l.getBooking().getId().equals(bid)).findFirst().orElse(null);
                actions.add(new ScheduleActionDto(
                        "book_prop_" + bp.getId(),
                        "CONFIRM_RESCHEDULE",
                        "Подтвердить перенос",
                        "Предложено новое время для занятия «" + courseTitle + "»: " + formatInstant(bp.getStartAt(), ZoneId.of("UTC")),
                        "high",
                        relatedLesson != null ? relatedLesson.getId() : null,
                        bid,
                        new ScheduleActionDto.ActionRef("Подтвердить", "POST", "/api/v1/bookings/" + bid + "/proposals/" + bp.getId() + "/accept"),
                        new ScheduleActionDto.ActionRef("Отклонить", "POST", "/api/v1/bookings/" + bid + "/proposals/" + bp.getId() + "/reject"),
                        Map.of("proposedStartAt", bp.getStartAt().toString(), "proposedEndAt", bp.getEndAt().toString())
                ));
            }
        }

        // 3. JOIN_LESSON — ближайшие joinable уроки
        List<Lesson> upcoming = lessonRepository.findForUserBetween(user.getId(), now, now.plusSeconds(24*3600));
        for (Lesson l : upcoming) {
            LessonDTO dto = lessonMapper.toDTO(l, user.getId(), locale, now);
            if (dto.canJoin()) {
                actions.add(new ScheduleActionDto(
                        "join_" + l.getId(),
                        "JOIN_LESSON",
                        "Присоединиться к уроку",
                        "Урок «" + dto.courseTitle() + "» начинается " + formatInstant(l.getStartAt(), ZoneId.of("UTC")),
                        "high",
                        l.getId(),
                        l.getBooking()!=null? l.getBooking().getId():null,
                        new ScheduleActionDto.ActionRef("Присоединиться", "POST", "/api/v1/lessons/" + l.getId() + "/join"),
                        null,
                        Map.of("startAt", l.getStartAt().toString())
                ));
            }
        }

        // 4. LEAVE_REVIEW — завершённые уроки с canReview
        List<Lesson> recentCompleted = lessonRepository.findForUserBetween(user.getId(),
                now.minusSeconds(30L*24*3600), now);
        for (Lesson l : recentCompleted) {
            if (l.getStatus() != Lesson.Status.COMPLETED) continue;
            LessonDTO dto = lessonMapper.toDTO(l, user.getId(), locale, now);
            if (dto.canReview()) {
                actions.add(new ScheduleActionDto(
                        "review_" + l.getId(),
                        "LEAVE_REVIEW",
                        "Оставить отзыв",
                        "Занятие по курсу «" + dto.courseTitle() + "» завершено — поделитесь впечатлением",
                        "low",
                        l.getId(),
                        l.getBooking()!=null? l.getBooking().getId():null,
                        new ScheduleActionDto.ActionRef("Оставить отзыв", "POST", "/api/v1/lessons/" + l.getId() + "/review"),
                        new ScheduleActionDto.ActionRef("Позже", "GET", "/api/v1/courses/" + dto.courseId()),
                        Map.of("courseId", dto.courseId() != null ? dto.courseId().toString() : "")
                ));
            }
        }

        // Сортировка по приоритету high->medium->low и по времени создания
        Map<String,int[]> priorityOrder = Map.of("high", new int[]{0}, "medium", new int[]{1}, "low", new int[]{2});
        actions.sort((a,b) -> {
            int pa = priorityOrder.getOrDefault(a.priority(), new int[]{9})[0];
            int pb = priorityOrder.getOrDefault(b.priority(), new int[]{9})[0];
            if (pa != pb) return Integer.compare(pa,pb);
            return a.type().compareTo(b.type());
        });

        return new ScheduleMeDtos.ActionsResponse(actions);
    }

    private String formatInstant(Instant ins, ZoneId zone) {
        if (ins == null) return "";
        return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(zone).format(ins);
    }
}
