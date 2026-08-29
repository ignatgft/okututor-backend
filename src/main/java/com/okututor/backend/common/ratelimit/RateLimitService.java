package com.okututor.backend.common.ratelimit;

import com.okututor.backend.common.config.AppProperties;
import com.okututor.backend.common.error.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {

    private final RateLimiter delegate;
    private final AppProperties properties;

    private final Map<String, Deque<Instant>> localBuckets = new ConcurrentHashMap<>();

    /**
     * Инжектируем {@link RedisRateLimiter} как опциональный бин: если Redis
     * недоступен или {@code app.rate-limit.use-redis=false}, используется
     * in-memory {@link LocalRateLimiter}.
     */
    @Autowired
    public RateLimitService(Optional<RateLimiter> redisRateLimiter, AppProperties properties) {
        this.properties = properties;
        this.delegate = properties.getRateLimit().isUseRedis()
                ? redisRateLimiter.orElse(null)
                : null;
    }

    public void checkLogin(String ip) {
        acquire("login:" + ip, properties.getRateLimit().getLoginPerMinute(), Duration.ofMinutes(1));
    }

    public void checkRegister(String ip) {
        acquire("register:" + ip, properties.getRateLimit().getRegisterPerHour(), Duration.ofHours(1));
    }

    public void checkVerify(String email) {
        acquire("verify:" + email.toLowerCase(), properties.getRateLimit().getVerifyPerHour(), Duration.ofHours(1));
    }

    public void checkResend(String email) {
        acquire("resend:" + email.toLowerCase(), properties.getRateLimit().getResendPerMinute(), Duration.ofMinutes(1));
    }

    public void checkForgotPassword(String email) {
        acquire("forgot:" + email.toLowerCase(), properties.getRateLimit().getForgotPasswordPerHour(), Duration.ofHours(1));
    }

    private void acquire(String key, int limit, Duration window) {
        if (!properties.getRateLimit().isEnabled()) {
            return;
        }
        RateLimiter limiter = delegate != null ? delegate : new LocalRateLimiter(localBuckets);
        if (!limiter.tryAcquire(key, limit, window)) {
            throw ApiException.rateLimited("Too many requests. Please slow down.");
        }
    }

    /** sliding-window лимитер для случая без Redis; атомарность per-key через compute. */
    static class LocalRateLimiter implements RateLimiter {

        private final Map<String, Deque<Instant>> buckets;

        LocalRateLimiter(Map<String, Deque<Instant>> buckets) {
            this.buckets = buckets;
        }

        @Override
        public boolean tryAcquire(String key, int limit, Duration window) {
            Instant now = Instant.now();
            // compute выполняет лямбду атомарно для ключа: блокировка только
            // по конкретному бакету, а не один монитор на все ключи
            boolean[] allowed = new boolean[1];
            buckets.compute(key, (k, hits) -> {
                Deque<Instant> deque = hits == null ? new ArrayDeque<>() : hits;
                while (!deque.isEmpty() && deque.peekFirst().isBefore(now.minus(window))) {
                    deque.pollFirst();
                }
                if (deque.size() >= limit) {
                    allowed[0] = false;
                    return deque;
                }
                deque.addLast(now);
                allowed[0] = true;
                return deque;
            });
            return allowed[0];
        }
    }
}
