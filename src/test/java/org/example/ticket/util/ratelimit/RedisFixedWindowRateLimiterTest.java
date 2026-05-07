package org.example.ticket.util.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisFixedWindowRateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private SimpleMeterRegistry meterRegistry;
    private RedisFixedWindowRateLimiter limiter;
    private RateLimitPolicy policy;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        Clock fixedClock = Clock.fixed(Instant.parse("2026-05-07T10:00:00Z"), ZoneOffset.UTC);
        limiter = new RedisFixedWindowRateLimiter(redisTemplate, meterRegistry, fixedClock);
        policy = new RateLimitPolicy("sms.certificate.ip", "/api/sms/certificate", "ip", 2, Duration.ofSeconds(60));

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.getExpire(any(), eq(TimeUnit.SECONDS))).thenReturn(30L);
    }

    @Test
    void allowsRequestsWithinWindowLimit() {
        when(valueOperations.increment(any())).thenReturn(1L);
        when(redisTemplate.expire(any(), any(Duration.class))).thenReturn(true);

        RateLimitDecision decision = limiter.evaluate(policy, "198.51.100.10");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(1L);
        assertThat(decision.retryAfterSeconds()).isEqualTo(30L);
    }

    @Test
    void rejectsRequestsOverWindowLimitAndIncrementsMetric() {
        when(valueOperations.increment(any())).thenReturn(3L);

        RateLimitDecision decision = limiter.evaluate(policy, "198.51.100.10");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("limit_exceeded");
        assertThat(meterRegistry.get("ticket.rate_limit.rejects")
                .tag("endpoint", "/api/sms/certificate")
                .tag("policy", "sms.certificate.ip")
                .tag("keyType", "ip")
                .tag("reason", "limit_exceeded")
                .counter()
                .count()).isEqualTo(1.0d);
    }
}
