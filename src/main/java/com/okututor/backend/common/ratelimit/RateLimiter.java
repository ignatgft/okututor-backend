package com.okututor.backend.common.ratelimit;

/**
 * абстракция rate-limiter с фиксированным окном. Реализацию на Redis можно
 * включается для multi-instance деплоя (app.rate-limit.use-redis=true).
 */
public interface RateLimiter {

    /**
     * регистрирует хит и возвращает true, пока вызывающий укладывается в лимит.
     */
    boolean tryAcquire(String key, int limit, java.time.Duration window);
}
