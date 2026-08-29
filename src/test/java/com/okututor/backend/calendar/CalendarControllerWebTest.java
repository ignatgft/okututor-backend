package com.okututor.backend.calendar;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.okututor.backend.common.config.JacksonConfig;
import com.okututor.backend.security.JwtService;
import com.okututor.backend.user.UserRepository;
import com.okututor.backend.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Календарь — защищённый endpoint: без аутентификации ожидаем 401.
 * Поведение агрегации/валидации диапазона покрыто CalendarServiceTest.
 */
@WebMvcTest(CalendarController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
class CalendarControllerWebTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CalendarService calendarService;

    @MockitoBean
    UserService userService;

    @MockitoBean
    JwtService jwtService;

    @MockitoBean
    UserRepository userRepository;

    @Test
    void calendar_withoutAuth_is401() throws Exception {
        mockMvc.perform(get("/api/v1/calendar")
                        .param("from", "2026-09-01T00:00:00Z")
                        .param("to", "2026-10-01T00:00:00Z"))
                .andExpect(status().isUnauthorized());
    }
}
