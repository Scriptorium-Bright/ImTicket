package org.example.ticket.reservation.queue.repository.redis;

import org.example.ticket.reservation.queue.repository.ReservationQueueExpiryIndex;
import org.example.ticket.reservation.queue.exception.ReservationQueueStorageException;

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

    /**
     * active 회차와 deadline ZSET을 읽을 Redis 의존성을 주입한다.
     * key factory를 통해 admission 저장소와 동일한 key 형식을 사용한다.
     */
    public RedisReservationQueueExpiryIndex(
            StringRedisTemplate redisTemplate,
            ReservationQueueKeyFactory keyFactory
    ) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.keyFactory = Objects.requireNonNull(keyFactory, "keyFactory must not be null");
    }

    /**
     * active performance ZSET의 모든 회차 ID를 읽는다.
     * 숫자로 변환할 수 없는 값은 저장소 손상으로 처리한다.
     */
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

    /**
     * 회차 deadline ZSET에서 기준 시각까지의 ticket을 조회한다.
     * limit을 적용해 한 번의 만료 처리량을 제한한다.
     */
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

    /**
     * 기준 시각까지 보존 기간이 끝난 회차를 active ZSET에서 제거한다.
     * 다음 scan이 유효한 회차만 순회하게 한다.
     */
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
