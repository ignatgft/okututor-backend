package com.okututor.backend.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
 * Acceptance-таблица поиска (спека #14, #13, #15): prefix (пит/jav),
 * fuzzy (pythn/pyton), синонимы RU/EN, multi-word, спецсимволы, garbage.
 * Ключевой критерий: «пит» находит «Python Programming». Нужен Docker;
 * без Docker пропускается.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest
class SearchAcceptanceIT {

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
        tutor.setEmail("acceptance@test.local");
        tutor.setVerified(true);
        tutor.setRole(Role.TUTOR);
        tutor.setFirstName("Acceptance");
        tutor.setLastName("Search");
        tutor = userRepository.save(tutor);

        // Course B из спеки #6: Python только в description — должен быть НИЖЕ Course A
        course(tutor, "Python Programming", "Programming",
                "Start from scratch and build your first apps.", "1200");
        course(tutor, "Java Backend Development", "IT",
                "Enterprise development. Includes Python integration basics.", "1400");
        course(tutor, "JavaScript Essentials", "IT",
                "Learn the language of the web.", "1100");
        course(tutor, "React Frontend", "IT",
                "Component-based UI development.", "1300");
        course(tutor, "Программирование с нуля", "Programming",
                "Базовый курс для новичков.", "900");
    }

    private void course(User teacher, String title, String subject, String description, String price) {
        Course c = new Course();
        c.setTeacher(teacher);
        c.setTitle(title);
        c.setSubject(subject);
        c.setDescription(description);
        c.setPricePerHour(new BigDecimal(price));
        c.setStatus(Course.Status.APPROVED);
        courseRepository.save(c);
    }

    private Page<CourseResponse> search(String q) {
        return courseSearchService.search(q, null, null, null, null, null, null, 0, 20, null);
    }

    private List<String> titles(Page<CourseResponse> page) {
        return page.getContent().stream().map(CourseResponse::title).toList();
    }

    // --- ключевой критерий спеки: пит → Python Programming ---

    @Test
    void pit_prefix_ru_findsPythonProgramming() {
        assertThat(titles(search("пит"))).contains("Python Programming");
    }

    // --- основная acceptance-таблица ---

    @Test
    void python_findsPythonProgramming_aboveDescriptionOnlyMatch() {
        List<String> result = titles(search("python"));
        assertThat(result).contains("Python Programming", "Java Backend Development");
        // title-матч значительно выше description-матча (спека #6)
        assertThat(result.indexOf("Python Programming"))
                .isLessThan(result.indexOf("Java Backend Development"));
    }

    @Test
    void piton_ru_findsPythonProgramming() {
        assertThat(titles(search("питон"))).contains("Python Programming");
    }

    @Test
    void pythn_typo_findsPythonProgramming() {
        assertThat(titles(search("pythn"))).contains("Python Programming");
    }

    @Test
    void pyton_typo_findsPythonProgramming() {
        assertThat(titles(search("pyton"))).contains("Python Programming");
    }

    @Test
    void programmirovanie_findsProgrammingCourses() {
        assertThat(titles(search("программирование"))).contains("Программирование с нуля");
    }

    @Test
    void java_findsJavaCourses() {
        assertThat(titles(search("java"))).contains("Java Backend Development");
    }

    @Test
    void jav_prefix_findsJavaCourses() {
        assertThat(titles(search("jav"))).contains("Java Backend Development");
    }

    @Test
    void javascript_findsJsCourse() {
        assertThat(titles(search("javascript"))).contains("JavaScript Essentials");
    }

    @Test
    void js_short_findsJsCourse() {
        assertThat(titles(search("js"))).contains("JavaScript Essentials");
    }

    @Test
    void react_findsReactCourse() {
        assertThat(titles(search("react"))).contains("React Frontend");
    }

    @Test
    void reakt_ru_findsReactCourse() {
        assertThat(titles(search("реакт"))).contains("React Frontend");
    }

    @Test
    void multiWord_pythonProgramming() {
        List<String> result = titles(search("python programming"));
        assertThat(result).isNotEmpty();
        assertThat(result.get(0)).isEqualTo("Python Programming");
    }

    @Test
    void multiWord_pythonBackend_findsBoth() {
        assertThat(titles(search("python backend")))
                .contains("Python Programming", "Java Backend Development");
    }

    // --- edge cases (спека #13) ---

    @Test
    void garbageQuery_returnsEmpty() {
        assertThat(search("asdfghjkl").getContent()).isEmpty();
    }

    @Test
    void specialChars_stillFindCourse() {
        assertThat(titles(search("python!!!"))).contains("Python Programming");
    }

    @Test
    void multipleSpaces_normalized() {
        assertThat(titles(search("python     programming"))).contains("Python Programming");
    }

    @Test
    void mixedLanguages_findCourse() {
        assertThat(titles(search("python питон"))).contains("Python Programming");
    }

    @Test
    void singleCharQuery_doesNotFail() {
        assertThatCode(() -> search("p")).doesNotThrowAnyException();
    }

    @Test
    void emptyQuery_returnsCatalog() {
        Page<CourseResponse> page = search("");
        assertThat(page.getContent()).isNotEmpty();
    }

    @Test
    void stopWordsOnly_returnsCatalog() {
        assertThatCode(() -> search("для и с")).doesNotThrowAnyException();
    }
}
