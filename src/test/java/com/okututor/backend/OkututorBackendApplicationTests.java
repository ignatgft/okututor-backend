package com.okututor.backend;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Проверяет, что Spring-контекст загружается без ошибок.
 * Требует PostgreSQL и Redis (Docker Compose или Testcontainers).
 */
@SpringBootTest
@ActiveProfiles("test")
@Disabled("Требует PostgreSQL + Redis (docker compose up)")
class OkututorBackendApplicationTests {

    @Test
    void contextLoads() {
        // если контекст не загрузился — тест упадёт
    }
}
