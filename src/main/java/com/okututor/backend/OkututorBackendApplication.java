package com.okututor.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Исключаем RedisRepositoriesAutoConfiguration, т.к. проект не использует
 * Redis-репозитории (@RedisHash / @EnableRedisRepositories). Без исключения
 * Spring Data сканирует все JPA-интерфейсы через Redis-модуль и выдаёт
 * warnings "Could not safely identify store assignment".
 */
@SpringBootApplication(exclude = RedisRepositoriesAutoConfiguration.class)
@EnableAsync
public class OkututorBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(OkututorBackendApplication.class, args);
    }
}
