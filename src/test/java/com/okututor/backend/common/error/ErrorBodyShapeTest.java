package com.okututor.backend.common.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * проверяет контракт тела ошибки, который парсит front_okututor errorMapper.js:
 * { status, message, error(code), errors(fieldErrors), traceId }.
 */
class ErrorBodyShapeTest {

    private MockMvc mvc;

    @RestController
    static class DummyController {

        @GetMapping("/dummy/not-found")
        public void notFound() {
            throw ApiException.notFound("Course not found");
        }

        @GetMapping("/dummy/conflict")
        public void conflict() {
            throw ApiException.conflict("Already booked");
        }

        @PostMapping("/dummy/validate")
        public void validate(@Valid @RequestBody Payload payload) {
        }
    }

    record Payload(@NotBlank @Email String email) {}

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new DummyController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void apiExceptionProducesStatusMessageErrorTraceId() throws Exception {
        mvc.perform(get("/dummy/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value(ErrorCodes.NOT_FOUND))
                .andExpect(jsonPath("$.message").value("Course not found"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void conflictMapsTo409() throws Exception {
        mvc.perform(get("/dummy/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(ErrorCodes.CONFLICT))
                .andExpect(jsonPath("$.message").value("Already booked"));
    }

    @Test
    void beanValidationReturns422WithFieldErrorsMap() throws Exception {
        mvc.perform(post("/dummy/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(ErrorCodes.VALIDATION_ERROR))
                .andExpect(jsonPath("$.errors.email").exists());
    }
}
