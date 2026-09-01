package com.okututor.backend.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.okututor.backend.booking.BookingRepository;
import com.okututor.backend.calendar.CalendarService;
import com.okututor.backend.course.Course;
import com.okututor.backend.enrollment.EnrollmentService;
import com.okututor.backend.lesson.LessonRepository;
import com.okututor.backend.lesson.LessonService;
import com.okututor.backend.tutors.AvailabilitySlot;
import com.okututor.backend.tutors.AvailabilitySlotRepository;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
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
import java.util.concurrent.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class FinalScheduleRegressionTest {

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

    private User user(String e, Role r){ User u=new User(); u.setEmail(e+UUID.randomUUID()); u.setVerified(true); u.setRole(r); u.setFirstName(r.name()); u.setLastName("T"); return userRepository.save(u); }
    private Course course(User t){ Course c=new Course(); c.setTeacher(t); c.setTitle("C"+UUID.randomUUID()); c.setSubject("Math"); c.setPricePerHour(BigDecimal.valueOf(1000)); c.setStatus(Course.Status.APPROVED); return courseRepository.save(c); }
    private void avail(User tutor, List<String> wdays){ for(String wd: wdays){ String canon=wd.substring(0,1).toUpperCase()+wd.substring(1).toLowerCase(); if(canon.equalsIgnoreCase("monday")) canon="Monday"; else if(canon.equalsIgnoreCase("tuesday")) canon="Tuesday"; else if(canon.equalsIgnoreCase("wednesday")) canon="Wednesday"; else if(canon.equalsIgnoreCase("thursday")) canon="Thursday"; else if(canon.equalsIgnoreCase("friday")) canon="Friday"; else if(canon.equalsIgnoreCase("saturday")) canon="Saturday"; else if(canon.equalsIgnoreCase("sunday")) canon="Sunday"; AvailabilitySlot s=new AvailabilitySlot(); s.setTutor(tutor); s.setWeekday(canon); s.setStartTime(LocalTime.of(8,0)); s.setEndTime(LocalTime.of(18,0)); s.setTimezone("UTC"); availabilitySlotRepository.save(s);} }
    private String computeEnd(String start,int count,List<String> days){ LocalDate s=LocalDate.parse(start); List<LocalDate> sess=new ArrayList<>(); LocalDate cur=s; int g=0; while(sess.size()<count && g<400){ String n=List.of("sunday","monday","tuesday","wednesday","thursday","friday","saturday").get(cur.getDayOfWeek().getValue()%7); if(days.contains(n)) sess.add(cur); cur=cur.plusDays(1); g++; } return sess.get(sess.size()-1).toString(); }
    private LocalDate next(DayOfWeek d){ LocalDate x=LocalDate.now().plusDays(5); while(x.getDayOfWeek()!=d) x=x.plusDays(1); return x; }

    // 15. Главный regression: REAL API FLOW
    @Test
    void regression_realApiFlow_8lessons_E2E(){
        User tutor=user("t@reg",Role.TUTOR); User student=user("s@reg",Role.STUDENT); Course c=course(tutor);
        avail(tutor, List.of("Monday","Wednesday","Friday"));
        var enr=enrollmentService.enroll(student,c.getId(),"hi",null); UUID app=enr.id(); enrollmentService.accept(tutor,app);
        String first=next(DayOfWeek.MONDAY).toString(); List<String> days=List.of("monday","wednesday","friday");
        String end=computeEnd(first,8,days);
        var slots=days.stream().map(d->new ScheduleService.SlotRequest(d.toUpperCase(),"10:00","11:00")).toList();
        // send total_lessons (count) as frontend now does — ensure not lost
        var req=new ScheduleService.ProposeRequest("UTC","ONLINE",first,end,60,slots,"msg",null,null,null,8,null);
        var prop=scheduleService.propose(tutor,app,req);
        // proposal still represents 8 (end_date computed from 8)
        assertThat(prop.start_date()).isEqualTo(first);
        assertThat(LocalDate.parse(prop.end_date())).isEqualTo(LocalDate.parse(end));
        var acc=scheduleService.accept(student,prop.id());
        assertThat(acc.created_count()).isEqualTo(8);
        // DB =8
        var dbLessons=lessonRepository.findByScheduleIdOrderByStartAtAsc(acc.schedule().id());
        assertThat(dbLessons).hasSize(8);
        // schedule API =8
        var viaSchedule=scheduleService.lessons(student, acc.schedule().id());
        assertThat(viaSchedule).hasSize(8);
        // lessons API =8 (paged)
        var viaLessons=lessonService.forUser(student,0,20);
        assertThat(viaLessons.getTotalElements()).isGreaterThanOrEqualTo(8);
        // calendar API =8
        Instant from=LocalDate.parse(first).atStartOfDay(ZoneId.of("UTC")).toInstant();
        Instant to=LocalDate.parse(end).plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant();
        var cal=calendarService.calendar(student, from, to, "UTC");
        long cnt=cal.stream().filter(ci-> ci.start_at()!=null && !ci.start_at().isBefore(from) && ci.start_at().isBefore(to)).count();
        assertThat(cnt).isGreaterThanOrEqualTo(8);
        // sequence 1..8 unique
        assertThat(dbLessons.stream().map(l->l.getSequenceNumber()).toList()).containsExactly(1,2,3,4,5,6,7,8);
        assertThat(dbLessons.stream().map(l->l.getId()).distinct().count()).isEqualTo(8);
        assertThat(dbLessons.stream().map(l->l.getStartAt()).distinct().count()).isEqualTo(8);
        Set<DayOfWeek> allowed=Set.of(DayOfWeek.MONDAY,DayOfWeek.WEDNESDAY,DayOfWeek.FRIDAY);
        for(var l: dbLessons) assertThat(allowed).contains(l.getStartAt().atZone(ZoneId.of("UTC")).getDayOfWeek());
        // retry still 8
        var acc2=scheduleService.accept(student,prop.id());
        assertThat(acc2.created_count()).isEqualTo(0);
        assertThat(lessonRepository.findByScheduleIdOrderByStartAtAsc(acc.schedule().id())).hasSize(8);
    }

    @Test
    void count_vs_range_equivalence(){
        User tutor=user("t@cnt",Role.TUTOR); User student=user("s@cnt",Role.STUDENT); Course c=course(tutor);
        avail(tutor, List.of("Monday","Wednesday","Friday"));
        // count-driven
        var enr1=enrollmentService.enroll(student,c.getId(),"hi",null); UUID app1=enr1.id(); enrollmentService.accept(tutor,app1);
        String first=next(DayOfWeek.MONDAY).toString(); List<String> days=List.of("monday","wednesday","friday");
        var slots=days.stream().map(d->new ScheduleService.SlotRequest(d.toUpperCase(),"10:00","11:00")).toList();
        var reqCount=new ScheduleService.ProposeRequest("UTC","ONLINE",first,null,60,slots,"m",null,null,null,8,null);
        // need to bypass null end_date validation — we compute end_date from count, so pass first as start and let service compute
        // For this test, use range-driven with computed end as comparison
        String end=computeEnd(first,8,days);
        var reqRange=new ScheduleService.ProposeRequest("UTC","ONLINE",first,end,60,slots,"m",null,null,null,null,null);
        // Both should be valid and produce 8
        var tutor2=user("t@cnt2",Role.TUTOR); var student2=user("s@cnt2",Role.STUDENT); Course c2=course(tutor2); avail(tutor2, List.of("Monday","Wednesday","Friday"));
        var enr2=enrollmentService.enroll(student2,c2.getId(),"hi",null); UUID app2=enr2.id(); enrollmentService.accept(tutor2,app2);
        var propRange=scheduleService.propose(tutor2,app2,reqRange);
        var accRange=scheduleService.accept(student2,propRange.id());
        assertThat(accRange.created_count()).isEqualTo(8);
        // count-driven via same service (if implemented) — test that count path also 8
        // Use fresh tutor/student to avoid conflict
        var tutor3=user("t@cnt3",Role.TUTOR); var student3=user("s@cnt3",Role.STUDENT); Course c3=course(tutor3); avail(tutor3, List.of("Monday","Wednesday","Friday"));
        var enr3=enrollmentService.enroll(student3,c3.getId(),"hi",null); UUID app3=enr3.id(); enrollmentService.accept(tutor3,app3);
        var propCount=scheduleService.propose(tutor3,app3,reqCount);
        var accCount=scheduleService.accept(student3,propCount.id());
        assertThat(accCount.created_count()).isEqualTo(8);
        // both ranges should be same size and same last date
        assertThat(propRange.end_date()).isEqualTo(propCount.end_date());
    }

    @Test
    void conflict_5th_slot_partial(){
        User tutor=user("t@conf",Role.TUTOR); User student=user("s@conf",Role.STUDENT); Course c=course(tutor);
        avail(tutor, List.of("Monday","Wednesday","Friday"));
        var enr=enrollmentService.enroll(student,c.getId(),"hi",null); UUID app=enr.id(); enrollmentService.accept(tutor,app);
        String first=next(DayOfWeek.MONDAY).toString(); List<String> days=List.of("monday","wednesday","friday");
        String end=computeEnd(first,8,days);
        // create existing booking that conflicts with 5th occurrence (which is Wednesday of week2)
        // 5th occurrence = Mon(1),Wed(2),Fri(3),Mon(4),Wed(5) => Wed week2
        LocalDate fifth = LocalDate.parse(first);
        int ccnt=0; LocalDate cur=LocalDate.parse(first); while(ccnt<5){ String n=List.of("sunday","monday","tuesday","wednesday","thursday","friday","saturday").get(cur.getDayOfWeek().getValue()%7); if(days.contains(n)) {ccnt++; if(ccnt==5) fifth=cur; } cur=cur.plusDays(1); }
        // create booking for tutor at fifth 10:00
        var existingCourse=course(tutor);
        var b=new com.okututor.backend.booking.Booking(); b.setCourse(existingCourse); b.setStudent(user("other",Role.STUDENT)); b.setTeacher(tutor); b.setStartAt(fifth.atTime(LocalTime.of(10,0)).atZone(ZoneId.of("UTC")).toInstant()); b.setEndAt(fifth.atTime(LocalTime.of(11,0)).atZone(ZoneId.of("UTC")).toInstant()); b.setDurationMinutes(60); b.setStatus(com.okututor.backend.booking.Booking.Status.CONFIRMED);
        // need to persist via repository
        bookingRepository.save(b);
        var slots=days.stream().map(d->new ScheduleService.SlotRequest(d.toUpperCase(),"10:00","11:00")).toList();
        var prop=scheduleService.propose(tutor,app,new ScheduleService.ProposeRequest("UTC","ONLINE",first,end,60,slots,"m",null,null,null));
        var acc=scheduleService.accept(student,prop.id());
        // one conflict, so 7 created, 1 conflicted — explicit partial, not silent success 8
        assertThat(acc.created_count()).isEqualTo(7);
        assertThat(acc.conflicted_dates()).hasSize(1);
        assertThat(acc.conflicted_dates().get(0)).contains(fifth.toString());
        assertThat(lessonRepository.findByScheduleIdOrderByStartAtAsc(acc.schedule().id())).hasSize(7);
    }

    @Test
    void concurrency_parallelAccept_still8() throws Exception{
        User tutor=user("t@conc",Role.TUTOR); User student=user("s@conc",Role.STUDENT); Course c=course(tutor);
        avail(tutor, List.of("Monday","Wednesday","Friday"));
        var enr=enrollmentService.enroll(student,c.getId(),"hi",null); UUID app=enr.id(); enrollmentService.accept(tutor,app);
        String first=next(DayOfWeek.MONDAY).toString(); List<String> days=List.of("monday","wednesday","friday");
        String end=computeEnd(first,8,days);
        var slots=days.stream().map(d->new ScheduleService.SlotRequest(d.toUpperCase(),"10:00","11:00")).toList();
        var prop=scheduleService.propose(tutor,app,new ScheduleService.ProposeRequest("UTC","ONLINE",first,end,60,slots,"m",null,null,null));
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch latch=new CountDownLatch(1);
        List<Throwable> errs=Collections.synchronizedList(new ArrayList<>());
        List<ScheduleService.AcceptResponse> results=Collections.synchronizedList(new ArrayList<>());
        for(int i=0;i<2;i++) pool.submit(()->{ try{ latch.await(); results.add(scheduleService.accept(student,prop.id())); }catch(Throwable t){ errs.add(t);} });
        latch.countDown(); pool.shutdown(); pool.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(errs).isEmpty();
        // one should have 8, other 0 (idempotent)
        long totalCreated = results.stream().mapToInt(r->r.created_count()).sum();
        assertThat(totalCreated).isEqualTo(8);
        assertThat(lessonRepository.findByScheduleIdOrderByStartAtAsc(prop.schedule_id()!=null? scheduleRepository.findByApplicationId(app).orElseThrow().getId() : results.get(0).schedule().id())).hasSize(8);
        // also check DB total still 8
        var lessons=lessonRepository.findByScheduleIdOrderByStartAtAsc(scheduleRepository.findByApplicationId(app).orElseThrow().getId());
        assertThat(lessons).hasSize(8);
    }

    @Test
    void pagination_8_20_100(){
        User tutor=user("t@pag",Role.TUTOR); User student=user("s@pag",Role.STUDENT); Course c=course(tutor);
        avail(tutor, List.of("Monday"));
        // create 8 via Monday only, count 8
        var enr=enrollmentService.enroll(student,c.getId(),"hi",null); UUID app=enr.id(); enrollmentService.accept(tutor,app);
        String first=next(DayOfWeek.MONDAY).toString();
        String end=computeEnd(first,8,List.of("monday"));
        var slots=List.of(new ScheduleService.SlotRequest("MONDAY","10:00","11:00"));
        var prop=scheduleService.propose(tutor,app,new ScheduleService.ProposeRequest("UTC","ONLINE",first,end,60,slots,"m",null,null,null));
        var acc=scheduleService.accept(student,prop.id());
        assertThat(acc.created_count()).isEqualTo(8);
        // /lessons pagination
        var p0=lessonService.forUser(student,0,5);
        assertThat(p0.getContent()).hasSize(5);
        var p1=lessonService.forUser(student,1,5);
        assertThat(p1.getContent()).hasSize(3);
        var pAll=lessonService.forUser(student,0,100);
        assertThat(pAll.getTotalElements()).isGreaterThanOrEqualTo(8);
        // schedule lessons no pagination limit (500)
        var viaSchedule=scheduleService.lessons(student, acc.schedule().id());
        assertThat(viaSchedule).hasSize(8);
    }

    @Test
    void timezone_variants(){
        for(String tz: List.of("UTC","Asia/Bishkek","Europe/Berlin","Asia/Almaty")){
            User tutor=user("t@tz"+tz,Role.TUTOR); User student=user("s@tz"+tz,Role.STUDENT); Course c=course(tutor);
            // availability in same tz as schedule so 09:00 local is covered
            for(String wd: List.of("Monday","Wednesday","Friday")){
                AvailabilitySlot s=new AvailabilitySlot(); s.setTutor(tutor); s.setWeekday(wd); s.setStartTime(LocalTime.of(8,0)); s.setEndTime(LocalTime.of(18,0)); s.setTimezone(tz); availabilitySlotRepository.save(s);
            }
            var enr=enrollmentService.enroll(student,c.getId(),"hi",null); UUID app=enr.id(); enrollmentService.accept(tutor,app);
            String first=next(DayOfWeek.MONDAY).toString();
            String end=computeEnd(first,8,List.of("monday","wednesday","friday"));
            var slots=List.of("MONDAY","WEDNESDAY","FRIDAY").stream().map(d->new ScheduleService.SlotRequest(d,"09:00","10:00")).toList();
            var prop=scheduleService.propose(tutor,app,new ScheduleService.ProposeRequest(tz,"ONLINE",first,end,60,slots,"m",null,null,null));
            var acc=scheduleService.accept(student,prop.id());
            assertThat(acc.created_count()).isEqualTo(8);
            // persisted Instant should be 09:00 in that tz
            var lessons=lessonRepository.findByScheduleIdOrderByStartAtAsc(acc.schedule().id());
            for(var l: lessons){
                ZonedDateTime zdt=l.getStartAt().atZone(ZoneId.of(tz));
                assertThat(zdt.getHour()).isEqualTo(9);
                assertThat(zdt.getMinute()).isEqualTo(0);
            }
        }
    }

    @Test
    void sequence_invariant_and_cancel(){
        User tutor=user("t@seq",Role.TUTOR); User student=user("s@seq",Role.STUDENT); Course c=course(tutor);
        avail(tutor, List.of("Monday","Wednesday","Friday"));
        var enr=enrollmentService.enroll(student,c.getId(),"hi",null); UUID app=enr.id(); enrollmentService.accept(tutor,app);
        String first=next(DayOfWeek.MONDAY).toString(); List<String> days=List.of("monday","wednesday","friday");
        String end=computeEnd(first,8,days);
        var slots=days.stream().map(d->new ScheduleService.SlotRequest(d.toUpperCase(),"10:00","11:00")).toList();
        var prop=scheduleService.propose(tutor,app,new ScheduleService.ProposeRequest("UTC","ONLINE",first,end,60,slots,"m",null,null,null));
        var acc=scheduleService.accept(student,prop.id());
        var lessons=lessonRepository.findByScheduleIdOrderByStartAtAsc(acc.schedule().id());
        assertThat(lessons.stream().map(l->l.getSequenceNumber()).toList()).containsExactly(1,2,3,4,5,6,7,8);
        // cancel #3
        var third=lessons.get(2);
        lessonService.cancel(tutor, third.getId(), "test cancel");
        var after=lessonRepository.findByScheduleIdOrderByStartAtAsc(acc.schedule().id());
        assertThat(after).hasSize(8);
        assertThat(after.stream().filter(l->l.getId().equals(third.getId())).findFirst().orElseThrow().getStatus().name()).isEqualTo("CANCELLED");
        // other 7 untouched and still SCHEDULED
        long scheduledCount=after.stream().filter(l->l.getStatus().name().equals("SCHEDULED")).count();
        assertThat(scheduledCount).isEqualTo(7);
        // sequence unchanged
        assertThat(after.stream().map(l->l.getSequenceNumber()).toList()).containsExactly(1,2,3,4,5,6,7,8);
        // reschedule #5
        var fifth=after.get(4);
        Instant newStart=fifth.getStartAt().plus(Duration.ofDays(30));
        Instant newEnd=newStart.plus(Duration.ofMinutes(60));
        var res=lessonService.reschedule(tutor, fifth.getId(), new LessonService.RescheduleRequest(newStart,newEnd));
        assertThat(res.start_at()).isEqualTo(newStart);
        var afterRes=lessonRepository.findByScheduleIdOrderByStartAtAsc(acc.schedule().id());
        assertThat(afterRes).hasSize(8);
        // complete #1
        lessonService.complete(tutor, after.get(0).getId());
        assertThat(lessonRepository.findById(after.get(0).getId()).orElseThrow().getStatus().name()).isEqualTo("COMPLETED");
    }

    @Test
    void meeting_independent_per_lesson(){
        User tutor=user("t@meet",Role.TUTOR); User student=user("s@meet",Role.STUDENT); Course c=course(tutor);
        avail(tutor, List.of("Monday","Wednesday","Friday"));
        var enr=enrollmentService.enroll(student,c.getId(),"hi",null); UUID app=enr.id(); enrollmentService.accept(tutor,app);
        String first=next(DayOfWeek.MONDAY).toString(); List<String> days=List.of("monday","wednesday","friday");
        String end=computeEnd(first,8,days);
        var slots=days.stream().map(d->new ScheduleService.SlotRequest(d.toUpperCase(),"10:00","11:00")).toList();
        var prop=scheduleService.propose(tutor,app,new ScheduleService.ProposeRequest("UTC","ONLINE",first,end,60,slots,"m",null,null,null));
        var acc=scheduleService.accept(student,prop.id());
        var lessons=lessonRepository.findByScheduleIdOrderByStartAtAsc(acc.schedule().id());
        // each lesson has distinct bookingId, meeting session independent
        Set<UUID> bookingIds=new HashSet<>();
        for(var l: lessons) bookingIds.add(l.getBooking().getId());
        assertThat(bookingIds).hasSize(8);
    }

    @Test
    void error_invalid_total_lessons(){
        User tutor=user("t@err",Role.TUTOR); User student=user("s@err",Role.STUDENT); Course c=course(tutor);
        avail(tutor, List.of("Monday"));
        var enr=enrollmentService.enroll(student,c.getId(),"hi",null); UUID app=enr.id(); enrollmentService.accept(tutor,app);
        String first=next(DayOfWeek.MONDAY).toString();
        var slots=List.of(new ScheduleService.SlotRequest("MONDAY","10:00","11:00"));
        assertThatThrownBy(()->scheduleService.propose(tutor,app,new ScheduleService.ProposeRequest("UTC","ONLINE",first,null,60,slots,"m",null,null,null,0,null)))
                .isInstanceOf(com.okututor.backend.common.error.ApiException.class)
                .satisfies(e-> assertThat(((com.okututor.backend.common.error.ApiException)e).getCode()).isEqualTo("INVALID_TOTAL_LESSONS"));
        assertThatThrownBy(()->scheduleService.propose(tutor,app,new ScheduleService.ProposeRequest("UTC","ONLINE",first,null,60,slots,"m",null,null,null,101,null)))
                .isInstanceOf(com.okututor.backend.common.error.ApiException.class)
                .satisfies(e-> assertThat(((com.okututor.backend.common.error.ApiException)e).getCode()).isEqualTo("INVALID_TOTAL_LESSONS"));
    }
}
