package org.example.ticket.reservation.queue.infrastructure.redis;

import org.example.ticket.reservation.queue.application.port.ReservationQueueExpiryIndex;
import org.example.ticket.reservation.queue.application.port.ReservationQueueStorageException;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Active performance registry와 deadline ZSET을 조회한다. */
public final class RedisReservationQueueExpiryIndex implements ReservationQueueExpiryIndex {

    private final StringRedisTemplate redisTemplate;
    private final ReservationQueueKeyFactory keyFactory;

    public RedisReservationQueueExpiryIndex(
            StringRedisTemplate redisTemplate,
            ReservationQueueKeyFactory keyFactory
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.keyFactory = Objects.requireNonNull(keyFactory, "keyFactory must not be null");
    }

    @Override
    public List<Long> activePerformanceTimeIds() {
        Set<String> values = redisTemplate.opsForZSet().range(
                keyFactory.activePerformanceTimes(),
                0,
                -1
        );
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        try {
            return values.stream()
                    .map(Long::parseLong)
                    .filter(value -> value > 0)
                    .toList();
        } catch (NumberFormatException exception) {
            throw new ReservationQueueStorageException("Active performance registry is invalid", exception);
        }
    }

    @Override
    public List<UUID> dueTicketIds(long performanceTimeId, Instant now, int limit) {
        Objects.requireNonNull(now, "now must not be null");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        Set<String> values = redisTemplate.opsForZSet().rangeByScore(
                keyFactory.deadline(performanceTimeId),
                Double.NEGATIVE_INFINITY,
                now.toEpochMilli(),
                0,
                limit
        );
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        try {
            return values.stream().map(UUID::fromString).toList();
        } catch (IllegalArgumentException exception) {
            throw new ReservationQueueStorageException("Queue deadline index is invalid", exception);
        }
    }

    @Override
    public void removeStalePerformanceTimes(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        redisTemplate.opsForZSet().removeRangeByScore(
                keyFactory.activePerformanceTimes(),
                Double.NEGATIVE_INFINITY,
                now.toEpochMilli()
        );
    }
}
