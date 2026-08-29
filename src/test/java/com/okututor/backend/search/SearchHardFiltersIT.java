package com.okututor.backend.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.okututor.backend.course.Course;
import com.okututor.backend.course.CourseRepository;
import com.okututor.backend.course.dto.CourseResponse;
import com.okututor.backend.user.Role;
import com.okututor.backend.user.User;
import com.okututor.backend.user.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Hard filters поиска должны применяться на уровне PostgreSQL (спека #12):
 * price_max=1000 не может вернуть курс за 1500/2000. Нужен Docker;
 * без Docker пропускается (как BookingConcurrencyIT).
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest
class SearchHardFiltersIT {

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine").withReuse(false);

    @Autowired
    CourseSearchService courseSearchService;

    @Autowired
    CourseRepository courseRepository;

    @Autowired
    UserRepository userRepository;

    @BeforeAll
    void setUpData() {
        User tutor = new User();
        tutor.setEmail("search-filters@test.local");
        tutor.setVerified(true);
        tutor.setRole(Role.TUTOR);
        tutor.setFirstName("Search");
        tutor.setLastName("Filters");
        tutor = userRepository.save(tutor);

        course(tutor, "Quantumtest A", "QuantumPhysics", "500", "4.50");
        course(tutor, "Quantumtest B", "QuantumPhysics", "1000", "3.00");
        course(tutor, "Quantumtest C", "QuantumPhysics", "1500", null);
        course(tutor, "Quantumtest D", "QuantumPhysics", "2000", null);
        course(tutor, "Brilliantphysics", "Physics", "800", null);
        course(tutor, "Квантмеханика для начинающих", "Physics", "900", null);
        course(tutor, "Unique Algebra Marker", "Mathematics", "700", null);
    }

    private void course(User teacher, String title, String subject, String price, String rating) {
        Course c = new Course();
        c.setTeacher(teacher);
        c.setTitle(title);
        c.setSubject(subject);
        c.setPricePerHour(new BigDecimal(price));
        c.setStatus(Course.Status.APPROVED);
        if (rating != null) {
            c.setAverageRating(new BigDecimal(rating));
        }
        courseRepository.save(c);
    }

    private Page<CourseResponse> search(String q, String subject, String locationType,
                                        BigDecimal maxPrice, BigDecimal priceMin, Double ratingMin) {
        return courseSearchService.search(q, subject, locationType, null, maxPrice, priceMin,
                ratingMin, 0, 20, null);
    }

    private List<BigDecimal> prices(Page<CourseResponse> page) {
        return page.getContent().stream().map(CourseResponse::price_per_hour).sorted().toList();
    }

    @Test
    void priceMax_excludesExpensiveCourses() {
        Page<CourseResponse> result = search("quantumtest", null, null, new BigDecimal("1000"), null, null);
        assertThat(result.getContent()).hasSize(2);
        assertThat(prices(result)).containsExactly(new BigDecimal("500.00"), new BigDecimal("1000.00"));
    }

    @Test
    void priceMin_excludesCheapCourses() {
        Page<CourseResponse> result = search("quantumtest", null, null, null, new BigDecimal("1500"), null);
        assertThat(result.getContent()).hasSize(2);
        assertThat(prices(result)).containsExactly(new BigDecimal("1500.00"), new BigDecimal("2000.00"));
    }

    @Test
    void priceRange_appliesBothBounds() {
        Page<CourseResponse> result = search("quantumtest", null, null,
                new BigDecimal("1500"), new BigDecimal("1000"), null);
        assertThat(prices(result)).containsExactly(new BigDecimal("1000.00"), new BigDecimal("1500.00"));
    }

    @Test
    void subjectFilter_isHard() {
        assertThat(search("quantumtest", "quantumphysics", null, null, null, null).getContent()).hasSize(4);
        assertThat(search("quantumtest", "biology", null, null, null, null).getContent()).isEmpty();
    }

    @Test
    void ratingMin_excludesUnratedAndLowRated() {
        Page<CourseResponse> result = search("quantumtest", null, null, null, null, 4.0);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).title()).isEqualTo("Quantumtest A");
    }

    @Test
    void locationTypeFilter_isHard() {
        assertThat(search("quantumtest", null, "offline", null, null, null).getContent()).isEmpty();
        assertThat(search("quantumtest", null, "online", null, null, null).getContent()).hasSize(4);
    }

    @Test
    void totalElements_reflectsFilteredCount() {
        Page<CourseResponse> result = search("quantumtest", null, null, new BigDecimal("1000"), null, null);
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    void ftsPrefix_matchesWordStart() {
        // "brilliant" должен найти "Brilliantphysics" через префикс brilliant:*
        Page<CourseResponse> result = search("brilliant", null, null, null, null, null);
        assertThat(result.getContent()).extracting(CourseResponse::title)
                .contains("Brilliantphysics");
    }

    @Test
    void trgm_typoTolerance_findsMisspelledTitle() {
        // опечатка: пропущена одна "l"
        Page<CourseResponse> result = search("briliantphysics", null, null, null, null, null);
        assertThat(result.getContent()).extracting(CourseResponse::title)
                .contains("Brilliantphysics");
    }

    @Test
    void russianFts_stemmingMatchesWordForms() {
        // "квантмеханике" (падежная форма) находит "Квантмеханика" через russian-стемминг
        Page<CourseResponse> result = search("квантмеханике", null, null, null, null, null);
        assertThat(result.getContent()).extracting(CourseResponse::title)
                .contains("Квантмеханика для начинающих");
    }

    @Test
    void russianQuery_findsCourseViaSynonym() {
        // "математика" → синоним "mathematics" матчит subject "Mathematics"
        Page<CourseResponse> result = search("математика", null, null, null, null, null);
        assertThat(result.getContent()).extracting(CourseResponse::title)
                .contains("Unique Algebra Marker");
    }

    @Test
    void hardFilter_appliesTogetherWithTextMatching() {
        // синоним-матчинг не отменяет hard filter по цене
        Page<CourseResponse> result = search("математика", null, null,
                new BigDecimal("100"), null, null);
        assertThat(result.getContent()).extracting(CourseResponse::title)
                .doesNotContain("Unique Algebra Marker");
    }
}
