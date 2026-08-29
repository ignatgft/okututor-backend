package com.okututor.backend.common.ratelimit;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBean(StringRedisTemplate.class)
public class RedisRateLimiter implements RateLimiter {

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        String redisKey = "rl:" + key;
        String bucketKey = redisKey + ":" + (System.currentTimeMillis() / window.toMillis());
        Long count = redis.opsForValue().increment(bucketKey);
        if (count != null && count == 1L) {
            redis.expire(bucketKey, window);
        }
        return count == null || count <= limit;
    }
}
