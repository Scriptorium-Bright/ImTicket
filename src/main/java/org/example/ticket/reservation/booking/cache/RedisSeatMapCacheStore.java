package org.example.ticket.reservation.booking.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** StringRedisTemplate과 JSON snapshot으로 좌석 cache를 구현한다. */
@RequiredArgsConstructor
public final class RedisSeatMapCacheStore implements SeatMapCacheStore {

    private static final TypeReference<List<SeatMapCacheEntry>> ENTRY_LIST_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SeatMapCacheKeyFactory keyFactory;

    /**
     * Redis JSON snapshot을 읽어 불변 cache entry 목록으로 복원한다.
     * Redis 오류와 JSON 오류를 같은 cache 경계 예외로 변환한다.
     */
    @Override
    public Optional<List<SeatMapCacheEntry>> get(long performanceTimeId) {
        String payload;
        try {
            payload = redisTemplate.opsForValue().get(keyFactory.snapshot(performanceTimeId));
        } catch (RuntimeException exception) {
            throw failure("read", performanceTimeId, exception);
        }
        if (payload == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(List.copyOf(objectMapper.readValue(payload, ENTRY_LIST_TYPE)));
        } catch (JsonProcessingException | RuntimeException exception) {
            throw failure("deserialize", performanceTimeId, exception);
        }
    }

    /**
     * cache entry 목록을 JSON으로 직렬화해 TTL과 함께 저장한다.
     * API 응답 DTO에는 영향을 주지 않는 별도 snapshot schema를 사용한다.
     */
    @Override
    public void put(long performanceTimeId, List<SeatMapCacheEntry> entries, Duration ttl) {
        try {
            String payload = objectMapper.writeValueAsString(entries);
            redisTemplate.opsForValue().set(keyFactory.snapshot(performanceTimeId), payload, ttl);
        } catch (JsonProcessingException | RuntimeException exception) {
            throw failure("write", performanceTimeId, exception);
        }
    }

    /**
     * 지정 회차의 Redis snapshot key를 삭제한다.
     * 삭제 실패는 listener가 metric으로 남기고 예약 결과는 유지한다.
     */
    @Override
    public void evict(long performanceTimeId) {
        try {
            redisTemplate.delete(keyFactory.snapshot(performanceTimeId));
        } catch (RuntimeException exception) {
            throw failure("evict", performanceTimeId, exception);
        }
    }

    /**
     * storage 동작명을 포함한 cache 경계 예외를 만든다.
     * 장애 지점과 공연 회차를 fallback 로그에서 식별할 수 있게 한다.
     */
    private SeatMapCacheException failure(String operation, long performanceTimeId, Exception cause) {
        return new SeatMapCacheException(
                "Seat map cache " + operation + " failed. performanceTimeId=" + performanceTimeId,
                cause
        );
    }
}
