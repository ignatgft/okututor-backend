package com.okututor.backend.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.enrollment.Enrollment;
import com.okututor.backend.enrollment.EnrollmentService;
import com.okututor.backend.lesson.LessonRepository;
import com.okututor.backend.tutors.AvailabilitySlot;
import com.okututor.backend.tutors.AvailabilitySlotRepository;
import com.okututor.backend.course.Course;
import com.okututor.backend.user.User;
import com.okututor.backend.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.UUID;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class ScheduleGenerationAuditTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine").withReuse(false);

    @Autowired EnrollmentService enrollmentService;
    @Autowired ScheduleService scheduleService;
    @Autowired com.okututor.backend.user.UserRepository userRepository;
    @Autowired com.okututor.backend.course.CourseRepository courseRepository;
    @Autowired com.okututor.backend.enrollment.EnrollmentRepository enrollmentRepository;
    @Autowired AvailabilitySlotRepository availabilitySlotRepository;
    @Autowired LessonRepository lessonRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired ScheduleRepository scheduleRepository;

    private User createUser(String email, Role role) {
        User u = new User();
        u.setEmail(email + UUID.randomUUID());
        u.setVerified(true);
        u.setRole(role);
        u.setFirstName(role.name());
        u.setLastName("Test");
        return userRepository.save(u);
    }

    private Course createCourse(User tutor) {
        Course c = new Course();
        c.setTeacher(tutor);
        c.setTitle("Test Course");
        c.setSubject("Math");
        c.setPricePerHour(BigDecimal.valueOf(1000));
        c.setStatus(Course.Status.APPROVED);
        return courseRepository.save(c);
    }

    private void setAvailability(User tutor, List<String> weekdays, LocalTime from, LocalTime to) {
        for (String wd : weekdays) {
            AvailabilitySlot s = new AvailabilitySlot();
            s.setTutor(tutor);
            s.setWeekday(wd.substring(0,1).toUpperCase()+wd.substring(1).toLowerCase()); // Monday etc
            // but AvailabilitySlot expects "Monday" capital first?
            // Let's set as stored: "Monday"
            s.setWeekday(wd.substring(0,1).toUpperCase()+wd.substring(1).toLowerCase());
            // Actually need to match what service expects: DisplayName FULL English e.g. Monday
            String canon = wd.equalsIgnoreCase("monday") ? "Monday" :
                           wd.equalsIgnoreCase("wednesday") ? "Wednesday" :
                           wd.equalsIgnoreCase("friday") ? "Friday" : wd;
            s.setWeekday(canon);
            s.setStartTime(from);
            s.setEndTime(to);
            s.setTimezone("UTC");
            availabilitySlotRepository.save(s);
        }
    }

    // Copy frontend computeEndDate
    private String computeEndDate(String startDate, int count, List<String> days) {
        LocalDate start = LocalDate.parse(startDate);
        List<LocalDate> sessions = new java.util.ArrayList<>();
        LocalDate cursor = start;
        int guard=0;
        while (sessions.size() < count && guard<366) {
            String name = List.of("sunday","monday","tuesday","wednesday","thursday","friday","saturday").get(cursor.getDayOfWeek().getValue()%7);
            if (days.contains(name)) sessions.add(cursor);
            cursor = cursor.plusDays(1);
            guard++;
        }
        LocalDate last = sessions.get(sessions.size()-1);
        return last.toString();
    }

    @Test
    void enrollAndSchedule_3weekdays_8lessons_generatesExactly8() {
        User tutor = createUser("tutor", Role.TUTOR);
        User student = createUser("student", Role.STUDENT);
        Course course = createCourse(tutor);
        // availability for Mon/Wed/Fri 10:00-15:00 UTC
        setAvailability(tutor, List.of("Monday","Wednesday","Friday"), LocalTime.of(10,0), LocalTime.of(15,0));

        // enroll (PENDING) – acceptAndSchedule will transition to ACCEPTED itself
        var enrollment = enrollmentService.enroll(student, course.getId(), "hello", null);
        UUID enrollmentId = enrollment.id();

        // next Monday from now
        LocalDate nextMonday = LocalDate.now().plusDays(8);
        while (nextMonday.getDayOfWeek() != DayOfWeek.MONDAY) nextMonday = nextMonday.plusDays(1);
        String firstDate = nextMonday.toString();
        List<String> days = List.of("monday","wednesday","friday");
        String endDate = computeEndDate(firstDate, 8, days);
        System.out.println("firstDate="+firstDate+" endDate="+endDate);

        // Use flat wizard payload (days/start_date/end_date)
        var req = new EnrollmentService.AcceptAndScheduleRequest(
                firstDate, "12:00", 60, "UTC", null,
                firstDate, endDate, days, null, "ONLINE", null, null, 8
        );
        var res = enrollmentService.acceptAndSchedule(tutor, enrollmentId, req);
        System.out.println("created_count="+res.created_count()+" conflicted="+res.conflicted_dates());
        assertThat(res.created_count()).isEqualTo(8);
        assertThat(res.conflicted_dates()).isEmpty();

        // Check DB: lessons
        var lessons = lessonRepository.findByStudentId(student.getId(), org.springframework.data.domain.PageRequest.of(0, 20));
        System.out.println("lessons totalElements="+lessons.getTotalElements());
        assertThat(lessons.getTotalElements()).isEqualTo(8);
        // sequence increasing and weekdays correct
        var allLessons = new java.util.ArrayList<>(lessonRepository.findByStudentId(student.getId(), org.springframework.data.domain.PageRequest.of(0, 100)).getContent());
        allLessons.sort(java.util.Comparator.comparing(l -> l.getStartAt()));
        assertThat(allLessons).hasSize(8);
        for (int i=1;i<allLessons.size();i++) {
            assertThat(allLessons.get(i).getStartAt()).isAfter(allLessons.get(i-1).getStartAt());
        }
        // all weekdays in set
        java.util.Set<DayOfWeek> allowed = java.util.Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY);
        for (var l : allLessons) {
            DayOfWeek dow = l.getStartAt().atZone(ZoneId.of("UTC")).getDayOfWeek();
            assertThat(allowed).contains(dow);
        }

        // API via lessons endpoint: should return 8
        // For acceptAndSchedule path, schedule is null (direct booking), so check bookings directly
        var bookings = bookingRepository.findByStudentIdOrderByStartAtDesc(student.getId(), org.springframework.data.domain.PageRequest.of(0, 20));
        System.out.println("bookings total="+bookings.getTotalElements());
        assertThat(bookings.getTotalElements()).isEqualTo(8);
    }

    @Test
    void schedulePropose_3weekdays_8lessons_firstDateIsTuesday_shouldStartWednesday() {
        User tutor = createUser("tutor2", Role.TUTOR);
        User student = createUser("student2", Role.STUDENT);
        Course course = createCourse(tutor);
        setAvailability(tutor, List.of("Monday","Wednesday","Friday"), LocalTime.of(10,0), LocalTime.of(15,0));
        var enrollment = enrollmentService.enroll(student, course.getId(), "hi", null);
        UUID appId = enrollment.id();
        enrollmentService.accept(tutor, appId);
        // firstDate = Tuesday (not selected)
        LocalDate tuesday = LocalDate.now().plusDays(8);
        while (tuesday.getDayOfWeek() != DayOfWeek.TUESDAY) tuesday = tuesday.plusDays(1);
        String firstDate = tuesday.toString();
        List<String> days = List.of("monday","wednesday","friday");
        String endDate = computeEndDate(firstDate, 8, days);
        System.out.println("tuesday firstDate="+firstDate+" endDate="+endDate);
        // Propose via ScheduleService
        var slots = days.stream().map(d -> new ScheduleService.SlotRequest(d.toUpperCase(), "12:00", "13:00")).toList();
        var req = new ScheduleService.ProposeRequest("UTC","ONLINE", firstDate, endDate, 60, slots, "msg", null,null,null);
        var proposal = scheduleService.propose(tutor, appId, req);
        // accept
        var accept = scheduleService.accept(student, proposal.id());
        System.out.println("propose accept created="+accept.created_count()+" conflicted="+accept.conflicted_dates());
        assertThat(accept.created_count()).isEqualTo(8);
        // first lesson should be Wednesday, not Tuesday
        var lessons = lessonRepository.findByScheduleIdOrderByStartAtAsc(accept.schedule().id());
        assertThat(lessons).hasSize(8);
        LocalDate firstLessonDate = lessons.get(0).getStartAt().atZone(ZoneId.of("UTC")).toLocalDate();
        System.out.println("firstLessonDate="+firstLessonDate+" dow="+firstLessonDate.getDayOfWeek());
        assertThat(firstLessonDate.getDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(firstLessonDate.isAfter(LocalDate.parse(firstDate)) || firstLessonDate.isEqual(LocalDate.parse(firstDate).plusDays(1))).isTrue();
    }
}
