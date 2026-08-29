package com.okututor.backend.search;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okututor.backend.common.config.JacksonConfig;
import com.okututor.backend.course.dto.CourseResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Контракт search-endpoint'ов: snake_case, валидация параметров, форма страницы.
 */
@WebMvcTest(SearchController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
class SearchControllerWebTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CourseSearchService courseSearchService;

    // JwtAuthenticationFilter попадает в web-срез; его зависимости гасим моками
    // (addFilters=false — сам фильтр в запросах не участвует)
    @MockitoBean
    com.okututor.backend.security.JwtService jwtService;

    @MockitoBean
    com.okututor.backend.user.UserRepository userRepository;

    private CourseResponse sampleCourse() {
        return new CourseResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Tutor User",
                "Java Basics",
                "IT",
                "Programming",
                "Learn Java",
                new BigDecimal("500.00"),
                "KGS",
                "online",
                "individual",
                List.of("monday"),
                List.of(),
                3,
                5,
                "APPROVED",
                null,
                null,
                new BigDecimal("4.50"),
                7,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private void mockSearchResult() {
        when(courseSearchService.search(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new PageImpl<>(List.of(sampleCourse()), PageRequest.of(0, 20), 1));
    }

    @Test
    void search_returnsSnakeCasePageResponse() throws Exception {
        mockSearchResult();
        mockMvc.perform(get("/api/v1/search/courses").param("q", "java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].teacher_name").value("Tutor User"))
                .andExpect(jsonPath("$.content[0].price_per_hour").value(500.00))
                .andExpect(jsonPath("$.content[0].average_rating").value(4.50))
                .andExpect(jsonPath("$.content[0].reviews_count").value(7))
                .andExpect(jsonPath("$.total_elements").value(1))
                .andExpect(jsonPath("$.total_pages").value(1));
    }

    @Test
    void search_passesFiltersToService() throws Exception {
        mockSearchResult();
        mockMvc.perform(get("/api/v1/search/courses")
                        .param("q", "java")
                        .param("subject", "IT")
                        .param("max_price", "1000")
                        .param("price_min", "100")
                        .param("rating_min", "4")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk());
        verify(courseSearchService).search(eq("java"), eq("IT"), any(), any(),
                eq(new BigDecimal("1000")), eq(new BigDecimal("100")), eq(4.0), eq(1), eq(10), any());
    }

    @Test
    void search_sizeBelowOne_is422() throws Exception {
        mockMvc.perform(get("/api/v1/search/courses").param("q", "java").param("size", "0"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.size").exists());
    }

    @Test
    void search_sizeAboveMax_is422() throws Exception {
        mockMvc.perform(get("/api/v1/search/courses").param("q", "java").param("size", "101"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void search_negativePage_is422() throws Exception {
        mockMvc.perform(get("/api/v1/search/courses").param("q", "java").param("page", "-1"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void search_ratingMinOutOfRange_is422() throws Exception {
        mockMvc.perform(get("/api/v1/search/courses").param("q", "java").param("rating_min", "6"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/v1/search/courses").param("q", "java").param("rating_min", "0.5"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void search_negativePrice_is422() throws Exception {
        mockMvc.perform(get("/api/v1/search/courses").param("q", "java").param("max_price", "-5"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/v1/search/courses").param("q", "java").param("price_min", "-1"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void search_tooLongQuery_is422() throws Exception {
        mockMvc.perform(get("/api/v1/search/courses").param("q", "a".repeat(201)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void suggestions_returnsCoursesAndTutors() throws Exception {
        when(courseSearchService.suggestions("java"))
                .thenReturn(List.of(Map.of("id", UUID.randomUUID(), "title", "Java Basics")));
        when(courseSearchService.tutorSuggestions("java"))
                .thenReturn(List.of(Map.of("id", UUID.randomUUID(), "full_name", "Tutor User")));
        mockMvc.perform(get("/api/v1/search/suggestions").param("q", "java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courses[0].title").value("Java Basics"))
                .andExpect(jsonPath("$.tutors[0].full_name").value("Tutor User"));
    }

    @Test
    void searchV2_returnsResultsExtractedFiltersAndExplanation() throws Exception {
        var extracted = new com.okututor.backend.search.understanding.StructuredQuery(
                com.okututor.backend.search.understanding.StructuredQuery.Intent.FIND_COURSE,
                "MATHEMATICS", null, "ORT", 10, "ONLINE",
                new BigDecimal("1000"), null, null, null, false);
        var v2 = new SearchV2Response(
                List.of(new SearchV2Response.CourseResult(sampleCourse(),
                        List.of("matched_subject", "price_within_budget"))),
                extracted, 0, 20, 1);
        when(courseSearchService.searchV2(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(v2);

        mockMvc.perform(get("/api/v1/search/courses/v2")
                        .param("q", "математика до 1000 онлайн"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].title").value("Java Basics"))
                .andExpect(jsonPath("$.results[0].price_per_hour").value(500.00))
                .andExpect(jsonPath("$.results[0].explanation[0]").value("matched_subject"))
                .andExpect(jsonPath("$.extracted_filters.subject").value("MATHEMATICS"))
                .andExpect(jsonPath("$.extracted_filters.price_max").value(1000))
                .andExpect(jsonPath("$.extracted_filters.grade").value(10))
                .andExpect(jsonPath("$.total_elements").value(1));
    }

    @Test
    void searchV2_validationWorks() throws Exception {
        mockMvc.perform(get("/api/v1/search/courses/v2").param("q", "java").param("size", "0"))
                .andExpect(status().isUnprocessableEntity());
    }
}
