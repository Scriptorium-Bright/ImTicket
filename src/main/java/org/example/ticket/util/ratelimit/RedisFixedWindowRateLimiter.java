package org.example.ticket.util.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class RedisFixedWindowRateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public RedisFixedWindowRateLimiter(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this(redisTemplate, meterRegistry, Clock.systemUTC());
    }

    RedisFixedWindowRateLimiter(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public RateLimitDecision evaluate(RateLimitPolicy policy, String normalizedKey) {
        Instant now = clock.instant();
        long windowSeconds = policy.window().toSeconds();
        long bucket = now.getEpochSecond() / windowSeconds;
        String redisKey = buildRedisKey(policy, normalizedKey, bucket);

        Long currentCount = redisTemplate.opsForValue().increment(redisKey);
        if (currentCount == null) {
            throw new IllegalStateException("Redis increment returned null for key " + redisKey);
        }

        if (currentCount == 1L) {
            redisTemplate.expire(redisKey, policy.window());
        }

        long retryAfterSeconds = resolveRetryAfterSeconds(redisKey, windowSeconds);
        long resetEpochSeconds = now.plusSeconds(retryAfterSeconds).getEpochSecond();
        long remaining = Math.max(0L, policy.limit() - currentCount);

        if (currentCount > policy.limit()) {
            meterRegistry.counter(
                    "ticket.rate_limit.rejects",
                    "endpoint", policy.endpoint(),
                    "policy", policy.policyName(),
                    "keyType", policy.keyType(),
                    "reason", "limit_exceeded"
            ).increment();
            log.warn(
                    "Rate limit rejected. correlationId={}, policy={}, keyType={}, keyHash={}, remaining={}, retryAfter={}, reason={}",
                    RateLimitLogSupport.correlationId(),
                    policy.policyName(),
                    policy.keyType(),
                    RateLimitLogSupport.redactKey(normalizedKey),
                    remaining,
                    retryAfterSeconds,
                    "limit_exceeded"
            );
            return RateLimitDecision.rejected(policy, remaining, retryAfterSeconds, resetEpochSeconds,
                    "limit_exceeded");
        }

        return RateLimitDecision.allowed(policy, remaining, retryAfterSeconds, resetEpochSeconds);
    }

    public void checkOrThrow(RateLimitPolicy policy, String normalizedKey) {
        RateLimitDecision decision = evaluate(policy, normalizedKey);
        if (!decision.allowed()) {
            throw new RateLimitException(decision);
        }
    }

    private String buildRedisKey(RateLimitPolicy policy, String normalizedKey, long bucket) {
        return "rl:%s:%s:%s:%d".formatted(
                policy.policyName(),
                policy.keyType(),
                normalizedKey,
                bucket
        );
    }

    private long resolveRetryAfterSeconds(String redisKey, long windowSeconds) {
        Long ttl = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
        if (ttl == null || ttl < 0) {
            return windowSeconds;
        }

        return Math.max(1L, ttl);
    }
}
