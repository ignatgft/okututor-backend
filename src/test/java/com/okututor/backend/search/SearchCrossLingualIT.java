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
 * Кросс-язычный поиск: запрос RU/KG/EN находит курсы с title/subject на других
 * языках с тем же смыслом (словари SynonymExpander + dual FTS + trgm).
 * Таблица из спеки: python/пайтон/питон/java/математика/мат/english/
 * англис тили/javascript. Нужен Docker; без Docker пропускается.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest
class SearchCrossLingualIT {

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
        tutor.setEmail("cross-lingual@test.local");
        tutor.setVerified(true);
        tutor.setRole(Role.TUTOR);
        tutor.setFirstName("Cross");
        tutor.setLastName("Lingual");
        tutor = userRepository.save(tutor);

        course(tutor, "Python Programming", "Programming", "1200");
        course(tutor, "Java Backend", "IT", "1400");
        course(tutor, "Математика ЕГЭ", "Mathematics", "900");
        course(tutor, "English for ORT", "English", "1000");
        course(tutor, "JavaScript Basics", "IT", "1100");
        course(tutor, "Пайтон для начинающих", "Programming", "800");
        // дистрактор: программирование без конкретного языка
        course(tutor, "Программирование для детей", "IT", "700");
    }

    private void course(User teacher, String title, String subject, String price) {
        Course c = new Course();
        c.setTeacher(teacher);
        c.setTitle(title);
        c.setSubject(subject);
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

    @Test
    void python_en_findsPythonCourses() {
        List<String> result = titles(search("python"));
        assertThat(result).contains("Python Programming");
        assertThat(result).doesNotContain("Программирование для детей");
    }

    @Test
    void pajton_ru_findsPythonCourses() {
        List<String> result = titles(search("пайтон"));
        assertThat(result).contains("Python Programming");
        assertThat(result).doesNotContain("Программирование для детей");
    }

    @Test
    void piton_ru_findsPythonCourses() {
        assertThat(titles(search("питон"))).contains("Python Programming");
    }

    @Test
    void java_findsJava_notOnlyGenericProgramming() {
        List<String> result = titles(search("java"));
        assertThat(result).contains("Java Backend");
        assertThat(result).doesNotContain("Программирование для детей");
    }

    @Test
    void matematika_ru_findsMath() {
        assertThat(titles(search("математика"))).contains("Математика ЕГЭ");
    }

    @Test
    void mat_prefix_findsMath() {
        assertThat(titles(search("мат"))).contains("Математика ЕГЭ");
    }

    @Test
    void math_en_findsMath() {
        assertThat(titles(search("math"))).contains("Математика ЕГЭ");
    }

    @Test
    void english_en_findsEnglishCourse() {
        assertThat(titles(search("english"))).contains("English for ORT");
    }

    @Test
    void anglisTili_kg_findsEnglishCourse() {
        assertThat(titles(search("англис тили"))).contains("English for ORT");
    }

    @Test
    void javascript_findsJsCourse() {
        assertThat(titles(search("javascript"))).contains("JavaScript Basics");
    }

    @Test
    void ruQuery_findsEnTitledCourse_viaSynonyms() {
        // «английский» → English for ORT
        assertThat(titles(search("английский"))).contains("English for ORT");
    }

    @Test
    void programmingQuery_findsGenericCourse() {
        // без tech-токена «программирование» матчит и общий курс
        assertThat(titles(search("программирование"))).contains("Программирование для детей");
    }

    @Test
    void pythonRanking_pythonCourseAboveGeneric() {
        List<String> result = titles(search("пайтон"));
        int pythonIdx = result.indexOf("Python Programming");
        int pajtonIdx = result.indexOf("Пайтон для начинающих");
        assertThat(pythonIdx).isNotNegative();
        assertThat(pajtonIdx).isNotNegative();
        // оба найдены; дистрактор «Программирование для детей» исключён
        assertThat(result).doesNotContain("Программирование для детей");
    }

    @Test
    void suggestions_prefixQueries_findRelevantCourses() {
        List<Object> paj = courseSearchService.suggestions("пай").stream()
                .map(m -> m.get("title")).toList();
        assertThat(paj).anyMatch(t -> t.toString().contains("Пайтон") || t.toString().contains("Python"));

        List<Object> mat = courseSearchService.suggestions("мат").stream()
                .map(m -> m.get("title")).toList();
        assertThat(mat).anyMatch(t -> t.toString().contains("Математика") || t.toString().contains("Math"));

        List<Object> jav = courseSearchService.suggestions("java").stream()
                .map(m -> m.get("title")).toList();
        assertThat(jav).anyMatch(t -> t.toString().contains("Java"));
    }
}
