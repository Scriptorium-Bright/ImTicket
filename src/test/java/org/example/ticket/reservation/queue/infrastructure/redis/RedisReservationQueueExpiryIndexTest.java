package org.example.ticket.reservation.queue.infrastructure.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisReservationQueueExpiryIndexTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, String> zSetOperations = mock(ZSetOperations.class);

    private RedisReservationQueueExpiryIndex index;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        index = new RedisReservationQueueExpiryIndex(redisTemplate, new ReservationQueueKeyFactory());
    }

    @Test
    void readsActivePerformanceIdsInRegistryOrder() {
        when(zSetOperations.range("reservation:queue:active-performance-times", 0, -1))
                .thenReturn(new LinkedHashSet<>(List.of("42", "43")));

        assertThat(index.activePerformanceTimeIds()).containsExactly(42L, 43L);
    }

    @Test
    void readsDueTicketIdsWithConfiguredLimit() {
        Instant now = Instant.parse("2026-08-10T10:10:00Z");
        UUID ticketId = UUID.fromString("f76f5ac8-a475-4e04-906a-1f54765f9770");
        when(zSetOperations.rangeByScore(
                "reservation:queue:{42}:deadline",
                Double.NEGATIVE_INFINITY,
                now.toEpochMilli(),
                0,
                200
        )).thenReturn(Set.of(ticketId.toString()));

        assertThat(index.dueTicketIds(42L, now, 200)).containsExactly(ticketId);
    }

    @Test
    void removesRegistryEntriesPastTheirRetentionHorizon() {
        Instant now = Instant.parse("2026-08-10T10:30:00Z");

        index.removeStalePerformanceTimes(now);

        verify(zSetOperations).removeRangeByScore(
                "reservation:queue:active-performance-times",
                Double.NEGATIVE_INFINITY,
                now.toEpochMilli()
        );
    }
}
