package org.example.ticket.reservation.booking.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** StringRedisTemplate과 versioned JSON snapshot으로 좌석 cache를 구현한다. */
@RequiredArgsConstructor
public final class RedisSeatMapCacheStore implements SeatMapCacheStore {

    private static final TypeReference<List<SeatMapCacheEntry>> ENTRY_LIST_TYPE = new TypeReference<>() {
    };

    private static final String INVALIDATE_SCRIPT_TEXT = """
            local nextVersion = redis.call('INCR', KEYS[1])
            redis.call('DEL', KEYS[2], KEYS[3])
            return nextVersion
            """;

    private static final String CONDITIONAL_WRITE_SCRIPT_TEXT = """
            local currentVersion = redis.call('GET', KEYS[1])
            if not currentVersion then
                currentVersion = '0'
            end
            if currentVersion ~= ARGV[1] then
                return 0
            end
            redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
            return 1
            """;

    private static final DefaultRedisScript<Long> INVALIDATE_SCRIPT =
            new DefaultRedisScript<>(INVALIDATE_SCRIPT_TEXT, Long.class);

    private static final DefaultRedisScript<Long> CONDITIONAL_WRITE_SCRIPT =
            new DefaultRedisScript<>(CONDITIONAL_WRITE_SCRIPT_TEXT, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SeatMapCacheKeyFactory keyFactory;

    /**
     * Redis version과 snapshot을 한 번의 MGET으로 읽고, 현재 version과 일치하는 snapshot만 반환한다.
     * v2 snapshot이 없을 때 migration 기간의 기존 raw list snapshot도 읽을 수 있다.
     */
    @Override
    public Optional<SeatMapCacheSnapshot> get(long performanceTimeId) {
        List<String> values;
        try {
            values = redisTemplate.opsForValue().multiGet(List.of(
                    keyFactory.version(performanceTimeId),
                    keyFactory.snapshot(performanceTimeId),
                    keyFactory.legacySnapshot(performanceTimeId)
            ));
        } catch (RuntimeException exception) {
            throw failure("read", performanceTimeId, exception);
        }

        if (values == null || values.size() < 3) {
            throw failure(
                    "read",
                    performanceTimeId,
                    new IllegalStateException("Redis MGET returned an incomplete result")
            );
        }

        long currentVersion = parseVersion(values.get(0), performanceTimeId);
        String versionedPayload = values.get(1);
        String legacyPayload = values.get(2);
        if (versionedPayload == null && legacyPayload != null && currentVersion != 0L) {
            // 구버전 key에는 생성 version이 없으므로 version 증가 이후에는 stale 여부를 판정할 수 없다.
            // compatibility listener가 version을 증가시킨 회차에서는 legacy payload를 miss로 취급한다.
            return Optional.empty();
        }
        String payload = versionedPayload != null ? versionedPayload : legacyPayload;
        if (payload == null) {
            return Optional.empty();
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root.isObject() && root.has("version") && root.has("entries")) {
                SeatMapCacheSnapshot snapshot = objectMapper.treeToValue(root, SeatMapCacheSnapshot.class);
                if (snapshot.version() != currentVersion) {
                    return Optional.empty();
                }
                return Optional.of(snapshot);
            }

            List<SeatMapCacheEntry> entries = objectMapper.convertValue(root, ENTRY_LIST_TYPE);
            return Optional.of(new SeatMapCacheSnapshot(currentVersion, entries));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw failure("deserialize", performanceTimeId, exception);
        }
    }

    /**
     * 회차 version key를 읽는다. 아직 invalidation이 발생하지 않은 회차는 version 0으로 취급한다.
     * Redis 연결·형 변환 오류는 cache 경계 예외로 전달한다.
     */
    @Override
    public long currentVersion(long performanceTimeId) {
        String rawVersion;
        try {
            rawVersion = redisTemplate.opsForValue().get(keyFactory.version(performanceTimeId));
        } catch (RuntimeException exception) {
            throw failure("version-read", performanceTimeId, exception);
        }
        return parseVersion(rawVersion, performanceTimeId);
    }

    /**
     * 현재 version이 expectedVersion과 같을 때만 versioned snapshot을 저장한다.
     * Redis Lua script가 version 비교와 SET을 하나의 원자 연산으로 수행한다.
     */
    @Override
    public boolean putIfVersionMatches(
            long performanceTimeId,
            long expectedVersion,
            List<SeatMapCacheEntry> entries,
            Duration ttl
    ) {
        try {
            String payload = objectMapper.writeValueAsString(new SeatMapCacheSnapshot(expectedVersion, entries));
            Long result = redisTemplate.execute(
                    CONDITIONAL_WRITE_SCRIPT,
                    List.of(
                            keyFactory.version(performanceTimeId),
                            keyFactory.snapshot(performanceTimeId)
                    ),
                    Long.toString(expectedVersion),
                    payload,
                    Long.toString(ttl.toMillis())
            );
            if (result == null) {
                throw new IllegalStateException("Redis conditional write returned null");
            }
            return result == 1L;
        } catch (JsonProcessingException | RuntimeException exception) {
            throw failure("conditional-write", performanceTimeId, exception);
        }
    }

    /**
     * version 증가와 v2·legacy snapshot 삭제를 하나의 Redis Lua script로 수행한다.
     * 반환된 version은 다음 snapshot 재구축의 기준으로 사용된다.
     */
    @Override
    public void evict(long performanceTimeId) {
        try {
            Long result = redisTemplate.execute(
                    INVALIDATE_SCRIPT,
                    List.of(
                            keyFactory.version(performanceTimeId),
                            keyFactory.snapshot(performanceTimeId),
                            keyFactory.legacySnapshot(performanceTimeId)
                    )
            );
            if (result == null) {
                throw new IllegalStateException("Redis invalidation returned null");
            }
        } catch (RuntimeException exception) {
            throw failure("evict", performanceTimeId, exception);
        }
    }

    /**
     * Redis 문자열 version을 숫자로 변환한다.
     * key가 없으면 초기 version 0을 반환하고 손상된 값은 cache 경계 예외로 전달한다.
     */
    private long parseVersion(String rawVersion, long performanceTimeId) {
        if (rawVersion == null) {
            return 0L;
        }
        try {
            return Long.parseLong(rawVersion);
        } catch (NumberFormatException exception) {
            throw failure("version-deserialize", performanceTimeId, exception);
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
