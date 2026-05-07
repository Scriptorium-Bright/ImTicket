package org.example.ticket.util.ratelimit;

import org.example.ticket.reservation.request.ReservationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreReserveGuardTest {

    @Mock
    private RedisFixedWindowRateLimiter rateLimiter;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private PreReserveGuard preReserveGuard;

    @BeforeEach
    void setUp() {
        preReserveGuard = new PreReserveGuard(
                rateLimiter,
                new ClientIpResolver(),
                stringRedisTemplate
        );
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void beginAppliesWalletPerformanceLimitAndAcquiresDedupe() {
        ReservationRequest request = new ReservationRequest(11L, java.util.List.of(3L, 1L, 2L));

        when(valueOperations.setIfAbsent(
                eq("dedupe:pre-reserve:0xabc:11:1,2,3"),
                eq("IN_PROGRESS"),
                any(java.time.Duration.class)
        )).thenReturn(true);

        try (PreReserveGuard.PreReserveExecution ignored = preReserveGuard.begin("0xABC", request)) {
            verify(rateLimiter).checkOrThrow(
                    eq(RateLimitPolicies.PRE_RESERVE_WALLET_PERFORMANCE),
                    eq("0xabc:11")
            );
            verify(valueOperations).setIfAbsent(
                    eq("dedupe:pre-reserve:0xabc:11:1,2,3"),
                    eq("IN_PROGRESS"),
                    any(java.time.Duration.class)
            );
        }
    }

    @Test
    void beginRejectsDuplicateInProgress() {
        ReservationRequest request = new ReservationRequest(15L, java.util.List.of(8L, 5L));

        when(valueOperations.setIfAbsent(
                eq("dedupe:pre-reserve:0xabc:15:5,8"),
                eq("IN_PROGRESS"),
                any(java.time.Duration.class)
        )).thenReturn(false);

        assertThatThrownBy(() -> preReserveGuard.begin("0xABC", request))
                .isInstanceOf(RateLimitException.class);
    }

    @Test
    void closeDeletesDedupeKeyOnFailure() {
        ReservationRequest request = new ReservationRequest(21L, java.util.List.of(9L));

        when(valueOperations.setIfAbsent(
                eq("dedupe:pre-reserve:0xabc:21:9"),
                eq("IN_PROGRESS"),
                any(java.time.Duration.class)
        )).thenReturn(true);

        PreReserveGuard.PreReserveExecution execution = preReserveGuard.begin("0xABC", request);
        execution.close();

        verify(stringRedisTemplate).delete("dedupe:pre-reserve:0xabc:21:9");
    }
}
