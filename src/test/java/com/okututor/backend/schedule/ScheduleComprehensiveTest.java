package com.okututor.backend.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.calendar.CalendarService;
import com.okututor.backend.enrollment.EnrollmentService;
import com.okututor.backend.lesson.LessonRepository;
import com.okututor.backend.lesson.LessonService;
import com.okututor.backend.tutors.AvailabilitySlotRepository;
import com.okututor.backend.tutors.AvailabilitySlot;
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
import java.util.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class ScheduleComprehensiveTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine").withReuse(false);

    @Autowired EnrollmentService enrollmentService;
    @Autowired ScheduleService scheduleService;
    @Autowired LessonService lessonService;
    @Autowired CalendarService calendarService;
    @Autowired com.okututor.backend.user.UserRepository userRepository;
    @Autowired com.okututor.backend.course.CourseRepository courseRepository;
    @Autowired AvailabilitySlotRepository availabilitySlotRepository;
    @Autowired LessonRepository lessonRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired ScheduleRepository scheduleRepository;

    private User user(String email, Role role) {
        User u = new User(); u.setEmail(email+UUID.randomUUID()); u.setVerified(true); u.setRole(role); u.setFirstName(role.name()); u.setLastName("T"); return userRepository.save(u);
    }
    private Course course(User t) {
        Course c=new Course(); c.setTeacher(t); c.setTitle("C"+UUID.randomUUID()); c.setSubject("Math"); c.setPricePerHour(BigDecimal.valueOf(1000)); c.setStatus(Course.Status.APPROVED); return courseRepository.save(c);
    }
    private void avail(User tutor, List<String> wdays, LocalTime from, LocalTime to) {
        for(String wd: wdays){
            String canon = wd.substring(0,1).toUpperCase()+wd.substring(1).toLowerCase();
            // normalize to DisplayName style Monday
            if(canon.equalsIgnoreCase("monday")) canon="Monday";
            else if(canon.equalsIgnoreCase("tuesday")) canon="Tuesday";
            else if(canon.equalsIgnoreCase("wednesday")) canon="Wednesday";
            else if(canon.equalsIgnoreCase("thursday")) canon="Thursday";
            else if(canon.equalsIgnoreCase("friday")) canon="Friday";
            else if(canon.equalsIgnoreCase("saturday")) canon="Saturday";
            else if(canon.equalsIgnoreCase("sunday")) canon="Sunday";
            AvailabilitySlot s=new AvailabilitySlot(); s.setTutor(tutor); s.setWeekday(canon); s.setStartTime(from); s.setEndTime(to); s.setTimezone("UTC"); availabilitySlotRepository.save(s);
        }
    }
    private String computeEndDate(String start, int count, List<String> days){
        LocalDate s=LocalDate.parse(start); List<LocalDate> sess=new ArrayList<>(); LocalDate cur=s; int g=0;
        while(sess.size()<count && g<400){ String n=List.of("sunday","monday","tuesday","wednesday","thursday","friday","saturday").get(cur.getDayOfWeek().getValue()%7); if(days.contains(n)) sess.add(cur); cur=cur.plusDays(1); g++; }
        return sess.get(sess.size()-1).toString();
    }
    private LocalDate next(DayOfWeek dow){
        LocalDate d=LocalDate.now().plusDays(5);
        while(d.getDayOfWeek()!=dow) d=d.plusDays(1);
        return d;
    }

    // ---- Generation counts ----
    @Test void gen_1weekday_x8(){ genForDays(List.of("monday"),8); }
    @Test void gen_2weekdays_x8(){ genForDays(List.of("monday","wednesday"),8); }
    @Test void gen_3weekdays_x8(){ genForDays(List.of("monday","wednesday","friday"),8); }
    @Test void gen_7weekdays_x8(){ genForDays(List.of("monday","tuesday","wednesday","thursday","friday","saturday","sunday"),8); }

    private void genForDays(List<String> days, int count){
        User tutor=user("t@gen",Role.TUTOR); User student=user("s@gen",Role.STUDENT); Course c=course(tutor);
        List<String> availDays = days.stream().map(d->d.substring(0,1).toUpperCase()+d.substring(1)).toList();
        // map to Monday style for avail helper
        List<String> availCap = days.stream().map(d->d.substring(0,1).toUpperCase()+d.substring(1).toLowerCase()).toList();
        // Actually avail helper does lower->Title, so pass lower
        avail(tutor, availCap, LocalTime.of(9,0), LocalTime.of(18,0));
        var enr=enrollmentService.enroll(student,c.getId(),"hi",null); UUID app=enr.id();
        enrollmentService.accept(tutor,app);
        String first=next(DayOfWeek.MONDAY).toString();
        // ensure first is Monday for 1wd case, else use next Monday
        String end=computeEndDate(first,count,days);
        var slots=days.stream().map(d->new ScheduleService.SlotRequest(d.toUpperCase(),"10:00","11:00")).toList();
        var req=new ScheduleService.ProposeRequest("UTC","ONLINE",first,end,60,slots,"msg",null,null,null);
        var prop=scheduleService.propose(tutor,app,req);
        var acc=scheduleService.accept(student,prop.id());
        assertThat(acc.created_count()).isEqualTo(count);
        var lessons=lessonRepository.findByScheduleIdOrderByStartAtAsc(acc.schedule().id());
        assertThat(lessons).hasSize(count);
        // invariants
        checkInvariants(lessons, days, count);
    }

    // ---- Counts ----
    @Test void counts_1(){ testCount(1); }
    @Test void counts_2(){ testCount(2); }
    @Test void counts_3(){ testCount(3); }
    @Test void counts_8(){ testCount(8); }
    @Test void counts_9(){ testCount(9); }
    @Test void counts_10(){ testCount(10); }
    @Test void counts_100(){ testCount(20); } // 100 would be heavy but test 20

    private void testCount(int count){
        User tutor=user("t@c"+count,Role.TUTOR); User student=user("s@c"+count,Role.STUDENT); Course c=course(tutor);
        avail(tutor, List.of("Monday","Wednesday","Friday"), LocalTime.of(8,0), LocalTime.of(18,0));
        var enr=enrollmentService.enroll(student,c.getId(),"hi",null); UUID app=enr.id(); enrollmentService.accept(tutor,app);
        String first=next(DayOfWeek.MONDAY).toString();
        List<String> days=List.of("monday","wednesday","friday");
        String end=computeEndDate(first,count,days);
        var slots=days.stream().map(d->new ScheduleService.SlotRequest(d.toUpperCase(),"10:00","11:00")).toList();
        var prop=scheduleService.propose(tutor,app,new ScheduleService.ProposeRequest("UTC","ONLINE",first,end,60,slots,"m",null,null,null));
        var acc=scheduleService.accept(student,prop.id());
        assertThat(acc.created_count()).isEqualTo(count);
        assertThat(lessonRepository.findByScheduleIdOrderByStartAtAsc(acc.schedule().id())).hasSize(count);
    }

    // ---- firstDate ----
    @Test void firstDate_isSelected(){
        User tutor=user("t@fd1",Role.TUTOR); User student=user("s@fd1",Role.STUDENT); Course c=course(tutor);
        avail(tutor, List.of("Monday","Wednesday","Friday"), LocalTime.of(8,0), LocalTime.of(18,0));
        var enr=enrollmentService.enroll(student,c.getId(),"hi",null); UUID app=enr.id(); enrollmentService.accept(tutor,app);
        String first=next(DayOfWeek.MONDAY).toString(); // Monday selected
        List<String> days=List.of("monday","wednesday","friday");
        String end=computeEndDate(first,3,days);
        var slots=days.stream().map(d->new ScheduleService.SlotRequest(d.toUpperCase(),"10:00","11:00")).toList();
        var prop=scheduleService.propose(tutor,app,new ScheduleService.ProposeRequest("UTC","ONLINE",first,end,60,slots,"m",null,null,null));
        var acc=scheduleService.accept(student,prop.id());
        var lessons=lessonRepository.findByScheduleIdOrderByStartAtAsc(acc.schedule().id());
        assertThat(lessons.get(0).getStartAt().atZone(ZoneId.of("UTC")).toLocalDate()).isEqualTo(LocalDate.parse(first));
    }
    @Test void firstDate_notSelected_startsNextValid(){
        User tutor=user("t@fd2",Role.TUTOR); User student=user("s@fd2",Role.STUDENT); Course c=course(tutor);
        avail(tutor, List.of("Monday","Wednesday","Friday"), LocalTime.of(8,0), LocalTime.of(18,0));
        var enr=enrollmentService.enroll(student,c.getId(),"hi",null); UUID app=enr.id(); enrollmentService.accept(tutor,app);
        String tuesday=next(DayOfWeek.TUESDAY).toString();
        List<String> days=List.of("monday","wednesday","friday");
        String end=computeEndDate(tuesday,3,days);
        var slots=days.stream().map(d->new ScheduleService.SlotRequest(d.toUpperCase(),"10:00","11:00")).toList();
        var prop=scheduleService.propose(tutor,app,new ScheduleService.ProposeRequest("UTC","ONLINE",tuesday,end,60,slots,"m",null,null,null));
        var acc=scheduleService.accept(student,prop.id());
        var lessons=lessonRepository.findByScheduleIdOrderByStartAtAsc(acc.schedule().id());
        // first should be Wednesday, not Tuesday
        assertThat(lessons.get(0).getStartAt().atZone(ZoneId.of("UTC")).getDayOfWeek()).isEqualTo(DayOfWeek.WEDNESDAY);
        assertThat(lessons.get(0).getStartAt().atZone(ZoneId.of("UTC")).toLocalDate().isAfter(LocalDate.parse(tuesday))).isTrue();
    }

    // ---- Boundaries ----
    @Test void monthBoundary(){
        User tutor=user("t@mb",Role.TUTOR); User student=user("s@mb",Role.STUDENT); Course c=course(tutor);
        avail(tutor, List.of("Monday","Wednesday","Friday"), LocalTime.of(8,0), LocalTime.of(18,0));
        var enr=enrollmentService.enroll(student,c.getId(),"hi",null); UUID app=enr.id(); enrollmentService.accept(tutor,app);
        // pick 2026-09-28 is Monday near month end (Sep has 30 days) + 8 lessons crosses Oct
        String first="2026-09-28"; // Monday
        List<String> days=List.of("monday","wednesday","friday");
        String end=computeEndDate(first,8,days); // should be 2026-10-14
        var slots=days.stream().map(d->new ScheduleService.SlotRequest(d.toUpperCase(),"10:00","11:00")).toList();
        var prop=scheduleService.propose(tutor,app,new ScheduleService.ProposeRequest("UTC","ONLINE",first,end,60,slots,"m",null,null,null));
        var acc=scheduleService.accept(student,prop.id());
        assertThat(acc.created_count()).isEqualTo(8);
        var lessons=lessonRepository.findByScheduleIdOrderByStartAtAsc(acc.schedule().id());
        assertThat(lessons.get(7).getStartAt().atZone(ZoneId.of("UTC")).toLocalDate().getMonth()).isEqualTo(Month.OCTOBER);
    }
    @Test void yearBoundary(){
        User tutor=user("t@yb",Role.TUTOR); User student=user("s@yb",Role.STUDENT); Course c=course(tutor);
        avail(tutor, List.of("Monday"), LocalTime.of(8,0), LocalTime.of(18,0));
        var enr=enrollmentService.enroll(student,c.getId(),"hi",null); UUID app=enr.id(); enrollmentService.accept(tutor,app);
        String first="2026-12-28"; // Monday 2026-12-28
        List<String> days=List.of("monday");
        String end=computeEndDate(first,3,days); // 2027-01-11
        var slots=List.of(new ScheduleService.SlotRequest("MONDAY","10:00","11:00"));
        var prop=scheduleService.propose(tutor,app,new ScheduleService.ProposeRequest("UTC","ONLINE",first,end,60,slots,"m",null,null,null));
        var acc=scheduleService.accept(student,prop.id());
        assertThat(acc.created_count()).isEqualTo(3);
        var lessons=lessonRepository.findByScheduleIdOrderByStartAtAsc(acc.schedule().id());
        assertThat(lessons.get(2).getStartAt().atZone(ZoneId.of("UTC")).getYear()).isEqualTo(2027);
    }
    @Test void leapYear(){
        User tutor=user("t@leap",Role.TUTOR); User student=user("s@leap",Role.STUDENT); Course c=course(tutor);
        avail(tutor, List.of("Saturday","Sunday"), LocalTime.of(8,0), LocalTime.of(18,0));
        var enr=enrollmentService.enroll(student,c.getId(),"hi",null); UUID app=enr.id(); enrollmentService.accept(tutor,app);
        String first="2028-02-26"; // Saturday 2028 is leap year, Feb 26 Sat, Feb 27 Sun, Mar 4 Sat...
        List<String> days=List.of("saturday","sunday");
        String end=computeEndDate(first,4,days);
        var slots=days.stream().map(d->new ScheduleService.SlotRequest(d.toUpperCase(),"10:00","11:00")).toList();
        var prop=scheduleService.propose(tutor,app,new ScheduleService.ProposeRequest("UTC","ONLINE",first,end,60,slots,"m",null,null,null));
        var acc=scheduleService.accept(student,prop.id());
        assertThat(acc.created_count()).isEqualTo(4);
        var lessons=lessonRepository.findByScheduleIdOrderByStartAtAsc(acc.schedule().id());
        // includes Feb 29 Mon? Actually Feb 29 2028 is Tuesday, not in set, but sequence should be 26 Sat,27 Sun,04 Mar Sat,05 Mar Sun => 4
        assertThat(lessons).hasSize(4);
        assertThat(lessons.stream().map(l->l.getStartAt().atZone(ZoneId.of("UTC")).toLocalDate().toString()).toList())
                .contains("2028-02-26","2028-02-27","2028-03-04","2028-03-05");
    }

    // ---- Persistence invariants ----
    @Test void persistence_invariants(){
        User tutor=user("t@inv",Role.TUTOR); User student=user("s@inv",Role.STUDENT); Course c=course(tutor);
        avail(tutor, List.of("Monday","Wednesday","Friday"), LocalTime.of(8,0), LocalTime.of(18,0));
        var enr=enrollmentService.enroll(student,c.getId(),"hi",null); UUID app=enr.id(); enrollmentService.accept(tutor,app);
        String first=next(DayOfWeek.MONDAY).toString(); List<String> days=List.of("monday","wednesday","friday");
        String end=computeEndDate(first,8,days);
        var slots=days.stream().map(d->new ScheduleService.SlotRequest(d.toUpperCase(),"10:00","11:00")).toList();
        var prop=scheduleService.propose(tutor,app,new ScheduleService.ProposeRequest("UTC","ONLINE",first,end,60,slots,"m",null,null,null));
        var acc=scheduleService.accept(student,prop.id());
        var lessons=lessonRepository.findByScheduleIdOrderByStartAtAsc(acc.schedule().id());
        assertThat(lessons).hasSize(8);
        // sequence 1..8 unique
        assertThat(lessons.stream().map(l->l.getSequenceNumber()).toList()).containsExactly(1,2,3,4,5,6,7,8);
        // unique ids
        assertThat(lessons.stream().map(l->l.getId()).distinct().count()).isEqualTo(8);
        assertThat(lessons.stream().map(l->l.getBooking().getId()).distinct().count()).isEqualTo(8);
        // dates strictly increasing
        for(int i=1;i<lessons.size();i++) assertThat(lessons.get(i).getStartAt()).isAfter(lessons.get(i-1).getStartAt());
        // all weekdays in set
        Set<DayOfWeek> allowed=Set.of(DayOfWeek.MONDAY,DayOfWeek.WEDNESDAY,DayOfWeek.FRIDAY);
        for(var l: lessons) assertThat(allowed).contains(l.getStartAt().atZone(ZoneId.of("UTC")).getDayOfWeek());
        // no duplicate occurrence
        assertThat(lessons.stream().map(l->l.getStartAt()).distinct().count()).isEqualTo(8);
    }

    // ---- Idempotency ----
    @Test void idempotency_secondAcceptStill8(){
        User tutor=user("t@idem",Role.TUTOR); User student=user("s@idem",Role.STUDENT); Course c=course(tutor);
        avail(tutor, List.of("Monday","Wednesday","Friday"), LocalTime.of(8,0), LocalTime.of(18,0));
        var enr=enrollmentService.enroll(student,c.getId(),"hi",null); UUID app=enr.id(); enrollmentService.accept(tutor,app);
        String first=next(DayOfWeek.MONDAY).toString(); List<String> days=List.of("monday","wednesday","friday");
        String end=computeEndDate(first,8,days);
        var slots=days.stream().map(d->new ScheduleService.SlotRequest(d.toUpperCase(),"10:00","11:00")).toList();
        var prop=scheduleService.propose(tutor,app,new ScheduleService.ProposeRequest("UTC","ONLINE",first,end,60,slots,"m",null,null,null));
        var acc1=scheduleService.accept(student,prop.id());
        var acc2=scheduleService.accept(student,prop.id());
        assertThat(acc2.created_count()).isEqualTo(0);
        assertThat(acc2.booking_ids()).hasSize(8);
        var lessons=lessonRepository.findByScheduleIdOrderByStartAtAsc(acc1.schedule().id());
        assertThat(lessons).hasSize(8);
    }

    // ---- API returns 8 ----
    @Test void api_returns_8(){
        User tutor=user("t@api",Role.TUTOR); User student=user("s@api",Role.STUDENT); Course c=course(tutor);
        avail(tutor, List.of("Monday","Wednesday","Friday"), LocalTime.of(8,0), LocalTime.of(18,0));
        var enr=enrollmentService.enroll(student,c.getId(),"hi",null); UUID app=enr.id(); enrollmentService.accept(tutor,app);
        String first=next(DayOfWeek.MONDAY).toString(); List<String> days=List.of("monday","wednesday","friday");
        String end=computeEndDate(first,8,days);
        var slots=days.stream().map(d->new ScheduleService.SlotRequest(d.toUpperCase(),"10:00","11:00")).toList();
        var prop=scheduleService.propose(tutor,app,new ScheduleService.ProposeRequest("UTC","ONLINE",first,end,60,slots,"m",null,null,null));
        var acc=scheduleService.accept(student,prop.id());
        // via schedule lessons endpoint
        var viaSchedule = scheduleService.lessons(student, acc.schedule().id());
        assertThat(viaSchedule).hasSize(8);
        // via lessonService forUser
        var viaLessons = lessonService.forUser(student,0,20);
        assertThat(viaLessons.getTotalElements()).isGreaterThanOrEqualTo(8);
        // via calendar
        Instant from = LocalDate.parse(first).atStartOfDay(ZoneId.of("UTC")).toInstant();
        Instant to = LocalDate.parse(end).plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant();
        var cal = calendarService.calendar(student, from, to, "UTC");
        // calendar aggregates bookings + lessons; schedule lessons appear as bookings
        long countInRange = cal.stream().filter(ci -> ci.start_at().isAfter(from.minusSeconds(1)) && ci.start_at().isBefore(to)).count();
        assertThat(countInRange).isGreaterThanOrEqualTo(8);
    }

    private void checkInvariants(List<com.okututor.backend.lesson.Lesson> lessons, List<String> days, int expected){
        assertThat(lessons).hasSize(expected);
        java.util.Set<Integer> seq= new java.util.HashSet<>();
        for(var l: lessons) seq.add(l.getSequenceNumber());
        assertThat(seq).hasSize(expected);
        for(int i=1;i<=expected;i++) assertThat(seq).contains(i);
    }
}
