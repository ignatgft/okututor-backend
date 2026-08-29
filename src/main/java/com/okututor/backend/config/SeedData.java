package com.okututor.backend.config;

import com.okututor.backend.common.config.AppProperties;
import com.okututor.backend.support.SupportService;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * идемпотентные демо/staging-данные (сценарии A–G из ТЗ). Управляется через
 * app.seed.enabled; каждый шаг сначала проверяет существование записей. Только dev;
 * дефолтные пароли ОБЯЗАТЕЛЬНО менять в реальных окружениях.
 */
@Component
@ConditionalOnProperty(prefix = "app.seed", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SeedData implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedData.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;
    private final com.okututor.backend.course.CourseRepository courseRepository;
    private final com.okututor.backend.booking.BookingRepository bookingRepository;
    private final com.okututor.backend.tutors.AvailabilitySlotRepository availabilityRepository;
    private final com.okututor.backend.tutors.TutorApplicationRepository applicationRepository;
    private final com.okututor.backend.notification.NotificationRepository notificationRepository;
    private final SupportService supportService;

    public SeedData(UserRepository userRepository,
                    PasswordEncoder passwordEncoder,
                    AppProperties properties,
                    com.okututor.backend.course.CourseRepository courseRepository,
                    com.okututor.backend.booking.BookingRepository bookingRepository,
                    com.okututor.backend.tutors.AvailabilitySlotRepository availabilityRepository,
                    com.okututor.backend.tutors.TutorApplicationRepository applicationRepository,
                    com.okututor.backend.notification.NotificationRepository notificationRepository,
                    SupportService supportService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.courseRepository = courseRepository;
        this.bookingRepository = bookingRepository;
        this.availabilityRepository = availabilityRepository;
        this.applicationRepository = applicationRepository;
        this.notificationRepository = notificationRepository;
        this.supportService = supportService;
    }

    @Override
    @Transactional
    public void run(String... args) {
        // пароли демо-аккаунтов задаются окружением (#11 спеки секретов);
        // дефолты существуют только для dev и НЕ должны использоваться в production
        User superAdmin = user("super@admin.test", "Super", "Admin", Role.SUPER_ADMIN,
                seedPassword("SEED_ADMIN_PASSWORD", "Admin#12345"));
        user("support@admin.test", "Support", "Agent", Role.ADMIN,
                seedPassword("SEED_SUPPORT_PASSWORD", "Support#12345"));

        if (userRepository.count() > 2) {
            log.info("[Seed] admin accounts present, skipping scenario data");
            return;
        }

        User tutor = user("tutor@test.com", "Tutor", "User", Role.TUTOR,
                seedPassword("SEED_TUTOR_PASSWORD", "Tutor#12345"));
        tutor.setBio("Experienced math and programming tutor.");
        tutor.setLocation("Bishkek");
        tutor.setExperienceYears(5);
        tutor.setEducation("KSU, Mathematics, 2018-2022");
        userRepository.save(tutor);
        application(tutor);

        availability(tutor, "Monday", "18:00", "22:00");
        availability(tutor, "Saturday", "10:00", "15:00");

        User student = user("test@test.com", "Test", "User", Role.STUDENT,
                seedPassword("SEED_STUDENT_PASSWORD", "Student#12345"));

        course(tutor, "Mathematics Basics", "Mathematics", 1500, "online", "individual",
                List.of("weekdays"), "Learn fundamental math concepts including algebra, geometry, and basic calculus.", 4.8);
        course(tutor, "English Conversation", "English", 2000, "online", "individual",
                List.of("weekends"), "Improve your English speaking skills with a native-level tutor.", 4.5);
        course(tutor, "Python Programming", "IT", 2500, "online", "group",
                List.of("weekdays", "weekends"), "Start your programming journey with Python fundamentals.", 4.9);

        booking(student, tutor, "Mathematics Basics", 1, 0, 60,
                com.okututor.backend.booking.Booking.Status.CONFIRMED);
        booking(student, tutor, "Python Programming", 3, 16, 90,
                com.okututor.backend.booking.Booking.Status.PENDING);

        supportService.create(student, new com.okututor.backend.support.dto.SupportTicketCreateRequest(
                "TECHNICAL",
                "Cannot join lesson",
                "I keep getting an error when trying to join my scheduled lesson.",
                "HIGH"));

        notify(student.getId(), "Welcome to Okututor!", "SYSTEM", null);
        notify(student.getId(), "Your booking was confirmed", "BOOKING", "/dashboard");

        log.info("[Seed] demo data created: super@admin.test / tutor@test.com / test@test.com");
    }

    /** пароль seed-аккаунта из окружения; дефолт — только для dev. */
    private static String seedPassword(String envVar, String devDefault) {
        return System.getenv().getOrDefault(envVar, devDefault);
    }

    private User user(String email, String first, String last, Role role, String rawPassword) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User u = new User();
            u.setEmail(email);
            u.setFirstName(first);
            u.setLastName(last);
            u.setRole(role);
            u.setVerified(true);
            u.setPasswordHash(passwordEncoder.encode(rawPassword));
            return userRepository.save(u);
        });
    }

    private void application(User tutor) {
        if (applicationRepository.findByUserId(tutor.getId()).isPresent()) {
            return;
        }
        var a = new com.okututor.backend.tutors.TutorApplication();
        a.setUser(tutor);
        a.setFullName(tutor.getFullName());
        a.setStatus(com.okututor.backend.tutors.TutorApplication.Status.APPROVED);
        a.setSubjects("Mathematics,IT");
        a.setLanguages("English,Russian,Kyrgyz");
        applicationRepository.save(a);
    }

    private void availability(User tutor, String weekday, String start, String end) {
        boolean exists = availabilityRepository.findByTutorIdOrderByWeekdayAscStartTimeAsc(tutor.getId()).stream()
                .anyMatch(s -> s.getWeekday().equals(weekday));
        if (exists) {
            return;
        }
        var slot = new com.okututor.backend.tutors.AvailabilitySlot();
        slot.setTutor(tutor);
        slot.setWeekday(weekday);
        slot.setStartTime(LocalTime.parse(start + ":00"));
        slot.setEndTime(LocalTime.parse(end + ":00"));
        availabilityRepository.save(slot);
    }

    private void course(User teacher, String title, String subject, int price, String locationType,
                        String groupSize, List<String> days, String description, double rating) {
        boolean exists = courseRepository.search(title.toLowerCase(), null, null, null, null,
                null, null, com.okututor.backend.course.Course.Status.APPROVED,
                org.springframework.data.domain.PageRequest.of(0, 20))
                .getContent().stream().anyMatch(c -> c.getTitle().equals(title));
        if (exists) {
            return;
        }
        var course = new com.okututor.backend.course.Course();
        course.setTeacher(teacher);
        course.setTitle(title);
        course.setSubject(subject);
        course.setDescription(description);
        course.setPricePerHour(BigDecimal.valueOf(price));
        course.setCurrency("KGS");
        course.setLocationType(com.okututor.backend.course.Course.LocationType.valueOf(locationType.toUpperCase()));
        course.setGroupSize(com.okututor.backend.course.Course.GroupSize.valueOf(groupSize.toUpperCase()));
        course.setDays(String.join(",", days));
        course.setMaxStudents("group".equals(groupSize) ? 10 : 1);
        course.setStatus(com.okututor.backend.course.Course.Status.APPROVED);
        course.setAverageRating(BigDecimal.valueOf(rating));
        courseRepository.save(course);
    }

    private void booking(User student, User teacher, String courseTitle, int daysAhead, int hour, int duration,
                         com.okututor.backend.booking.Booking.Status status) {
        var start = LocalDate.now(ZoneOffset.UTC).plusDays(daysAhead)
                .atTime(LocalTime.of(hour, 0)).toInstant(ZoneOffset.UTC);
        boolean alreadyBooked = !bookingRepository
                .findByTeacherIdOrderByStartAtDesc(teacher.getId(), org.springframework.data.domain.PageRequest.of(0, 50))
                .getContent().stream()
                .filter(b -> b.getStartAt() != null && b.getStartAt().equals(start))
                .toList()
                .isEmpty();
        if (alreadyBooked) {
            return;
        }
        var courseOpt = courseRepository
                .search(courseTitle.toLowerCase(), null, null, null, null,
                        null, null, com.okututor.backend.course.Course.Status.APPROVED,
                        org.springframework.data.domain.PageRequest.of(0, 20))
                .getContent().stream()
                .filter(c -> c.getTitle().equals(courseTitle))
                .findFirst();
        if (courseOpt.isEmpty()) {
            return;
        }
        try {
            var b = new com.okututor.backend.booking.Booking();
            b.setCourse(courseOpt.get());
            b.setStudent(student);
            b.setTeacher(teacher);
            b.setStartAt(start);
            b.setEndAt(start.plusSeconds(duration * 60L));
            b.setDurationMinutes(duration);
            b.setStatus(status);
            bookingRepository.save(b);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            log.debug("[Seed] booking slot taken, skipping");
        }
    }

    private void notify(java.util.UUID userId, String message, String type, String link) {
        boolean duplicate = notificationRepository.findByUserIdAndReadFalse(userId,
                        org.springframework.data.domain.PageRequest.of(0, 20))
                .stream().anyMatch(n -> message.equals(n.getMessage()));
        if (duplicate) {
            return;
        }
        var n = new com.okututor.backend.notification.Notification();
        n.setUser(userRepository.findById(userId).orElse(null));
        n.setMessage(message);
        n.setType(type);
        n.setLink(link);
        if (n.getUser() != null) {
            notificationRepository.save(n);
        }
    }
}
