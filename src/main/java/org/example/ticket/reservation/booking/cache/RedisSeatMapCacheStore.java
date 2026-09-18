package org.example.ticket.reservation.booking.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Redis String과 Hash로 좌석 layout·availability 읽기 모델을 구현한다. */
@RequiredArgsConstructor
public final class RedisSeatMapCacheStore implements SeatMapCacheStore {

    private static final String AVAILABILITY_GENERATION_FIELD = "__generation";
    private static final String AVAILABILITY_SEAT_COUNT_FIELD = "__seat_count";
    private static final String AVAILABILITY_SEPARATOR = "|";

    private static final String EVICT_SCRIPT_TEXT = """
            local nextLayoutGeneration = redis.call('INCR', KEYS[1])
            redis.call('INCR', KEYS[2])
            redis.call('DEL', KEYS[3], KEYS[4], KEYS[5], KEYS[6])
            return nextLayoutGeneration
            """;

    private static final String FULL_WRITE_SCRIPT_TEXT = """
            local layoutGeneration = redis.call('GET', KEYS[1])
            if not layoutGeneration then
                layoutGeneration = '0'
            end
            local availabilityGeneration = redis.call('GET', KEYS[2])
            if not availabilityGeneration then
                availabilityGeneration = '0'
            end
            if layoutGeneration ~= ARGV[1] or availabilityGeneration ~= ARGV[2] then
                return 0
            end
            redis.call('SET', KEYS[3], ARGV[3], 'PX', ARGV[4])
            redis.call('DEL', KEYS[4])
            redis.call('HSET', KEYS[4], '__generation', ARGV[2], '__seat_count', ARGV[5])
            for index = 6, #ARGV, 2 do
                redis.call('HSET', KEYS[4], ARGV[index], ARGV[index + 1])
            end
            redis.call('PEXPIRE', KEYS[4], ARGV[4])
            return 1
            """;

    private static final String AVAILABILITY_UPDATE_SCRIPT_TEXT = """
            if redis.call('EXISTS', KEYS[2]) == 0 then
                return 0
            end
            for index = 1, #ARGV, 3 do
                if redis.call('HEXISTS', KEYS[2], ARGV[index]) == 0 then
                    return -1
                end
            end
            local nextGeneration = redis.call('INCR', KEYS[1])
            for index = 1, #ARGV, 3 do
                local field = ARGV[index]
                local status = ARGV[index + 1]
                local incomingVersion = tonumber(ARGV[index + 2]) or 0
                local currentValue = redis.call('HGET', KEYS[2], field)
                local separator = string.find(currentValue, '|', 1, true)
                local currentVersion = 0
                if separator then
                    currentVersion = tonumber(string.sub(currentValue, separator + 1)) or 0
                end
                if incomingVersion >= currentVersion then
                    redis.call('HSET', KEYS[2], field, status .. '|' .. tostring(incomingVersion))
                end
            end
            redis.call('HSET', KEYS[2], '__generation', tostring(nextGeneration))
            return 1
            """;

    private static final DefaultRedisScript<Long> EVICT_SCRIPT =
            new DefaultRedisScript<>(EVICT_SCRIPT_TEXT, Long.class);
    private static final DefaultRedisScript<Long> FULL_WRITE_SCRIPT =
            new DefaultRedisScript<>(FULL_WRITE_SCRIPT_TEXT, Long.class);
    private static final DefaultRedisScript<Long> AVAILABILITY_UPDATE_SCRIPT =
            new DefaultRedisScript<>(AVAILABILITY_UPDATE_SCRIPT_TEXT, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SeatMapCacheKeyFactory keyFactory;

    /** Redis에서 layout·availability를 읽고 세대·좌석 집합을 검증한다.
     * 일관성이 확인된 경우에만 기존 좌석 응답 조합에 사용할 수 있는 값을 반환한다. */
    @Override
    public Optional<SeatMapCacheParts> get(long performanceTimeId) {
        try {
            List<String> values = redisTemplate.opsForValue().multiGet(List.of(
                    keyFactory.layoutGeneration(performanceTimeId),
                    keyFactory.layout(performanceTimeId),
                    keyFactory.availabilityGeneration(performanceTimeId)
            ));
            if (values == null || values.size() < 3) {
                throw new IllegalStateException("Redis MGET returned an incomplete result");
            }

            long layoutGeneration = parseGeneration(values.get(0), performanceTimeId, "layout");
            long availabilityGeneration = parseGeneration(values.get(2), performanceTimeId, "availability");
            String layoutPayload = values.get(1);
            if (layoutPayload == null) {
                return Optional.empty();
            }

            Map<Object, Object> rawAvailability = redisTemplate.opsForHash()
                    .entries(keyFactory.availability(performanceTimeId));
            if (rawAvailability == null || rawAvailability.isEmpty()) {
                return Optional.empty();
            }

            SeatLayoutCachePayload layout = objectMapper.readValue(layoutPayload, SeatLayoutCachePayload.class);
            if (layout.generation() != layoutGeneration) {
                return Optional.empty();
            }

            String hashGeneration = value(rawAvailability, AVAILABILITY_GENERATION_FIELD);
            String rawSeatCount = value(rawAvailability, AVAILABILITY_SEAT_COUNT_FIELD);
            if (hashGeneration == null || rawSeatCount == null
                    || Long.parseLong(hashGeneration) != availabilityGeneration
                    || Integer.parseInt(rawSeatCount) != layout.entries().size()) {
                return Optional.empty();
            }

            Map<Long, SeatAvailabilityCacheEntry> availabilityEntries = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> entry : rawAvailability.entrySet()) {
                String field = String.valueOf(entry.getKey());
                if (field.startsWith("__")) {
                    continue;
                }
                long seatId = Long.parseLong(field);
                availabilityEntries.put(seatId, decodeAvailability(seatId, String.valueOf(entry.getValue()), performanceTimeId));
            }
            if (availabilityEntries.size() != layout.entries().size()
                    || layout.entries().stream().map(SeatLayoutCacheEntry::id).anyMatch(id -> !availabilityEntries.containsKey(id))) {
                return Optional.empty();
            }
            return Optional.of(new SeatMapCacheParts(
                    layoutGeneration,
                    layout.entries(),
                    availabilityGeneration,
                    availabilityEntries
            ));
        } catch (SeatMapCacheException exception) {
            throw exception;
        } catch (JsonProcessingException | NumberFormatException exception) {
            throw failure("deserialize", performanceTimeId, exception);
        } catch (RuntimeException exception) {
            throw failure("read", performanceTimeId, exception);
        }
    }

    /** 현재 layout generation을 읽는다.
     * key가 없으면 아직 구조 변경이 없는 회차로 보고 0을 반환한다. */
    @Override
    public long currentLayoutGeneration(long performanceTimeId) {
        return readGeneration(keyFactory.layoutGeneration(performanceTimeId), performanceTimeId, "layout");
    }

    /** 현재 availability generation을 읽는다.
     * key가 없으면 초기 상태인 0을 반환한다. */
    @Override
    public long currentAvailabilityGeneration(long performanceTimeId) {
        return readGeneration(keyFactory.availabilityGeneration(performanceTimeId), performanceTimeId, "availability");
    }

    /** 두 generation을 확인한 뒤 정적 String과 동적 Hash를 함께 저장한다.
     * Lua script가 비교와 저장을 하나의 Redis 원자 연산으로 수행한다. */
    @Override
    public boolean putIfGenerationsMatch(
            long performanceTimeId,
            long expectedLayoutGeneration,
            long expectedAvailabilityGeneration,
            List<SeatLayoutCacheEntry> layoutEntries,
            List<SeatAvailabilityCacheEntry> availabilityEntries,
            Duration ttl
    ) {
        try {
            String layoutPayload = objectMapper.writeValueAsString(
                    new SeatLayoutCachePayload(expectedLayoutGeneration, layoutEntries)
            );
            List<Object> arguments = new ArrayList<>();
            arguments.add(Long.toString(expectedLayoutGeneration));
            arguments.add(Long.toString(expectedAvailabilityGeneration));
            arguments.add(layoutPayload);
            arguments.add(Long.toString(ttl.toMillis()));
            arguments.add(Integer.toString(availabilityEntries.size()));
            for (SeatAvailabilityCacheEntry entry : availabilityEntries) {
                arguments.add(Long.toString(entry.seatId()));
                arguments.add(encodeAvailability(entry));
            }

            Long result = redisTemplate.execute(
                    FULL_WRITE_SCRIPT,
                    List.of(
                            keyFactory.layoutGeneration(performanceTimeId),
                            keyFactory.availabilityGeneration(performanceTimeId),
                            keyFactory.layout(performanceTimeId),
                            keyFactory.availability(performanceTimeId)
                    ),
                    arguments.toArray()
            );
            if (result == null) {
                throw new IllegalStateException("Redis split cache write returned null");
            }
            return result == 1L;
        } catch (JsonProcessingException | RuntimeException exception) {
            throw failure("conditional-write", performanceTimeId, exception);
        }
    }

    /** 현재 availability Hash가 있을 때 변경 좌석의 상태만 갱신한다.
     * 각 좌석의 JPA version이 낮은 늦은 이벤트는 Lua script에서 무시한다. */
    @Override
    public boolean updateAvailability(long performanceTimeId, List<SeatAvailabilityCacheEntry> entries) {
        if (entries.isEmpty()) {
            return false;
        }
        try {
            List<Object> arguments = new ArrayList<>();
            for (SeatAvailabilityCacheEntry entry : entries) {
                arguments.add(Long.toString(entry.seatId()));
                arguments.add(entry.seatStatus().name());
                arguments.add(Long.toString(entry.seatVersion()));
            }
            Long result = redisTemplate.execute(
                    AVAILABILITY_UPDATE_SCRIPT,
                    List.of(
                            keyFactory.availabilityGeneration(performanceTimeId),
                            keyFactory.availability(performanceTimeId)
                    ),
                    arguments.toArray()
            );
            if (result == null) {
                throw new IllegalStateException("Redis availability update returned null");
            }
            return result == 1L;
        } catch (RuntimeException exception) {
            throw failure("availability-update", performanceTimeId, exception);
        }
    }

    /** layout·availability generation을 증가시키고 관련 cache key를 삭제한다.
     * 좌석 상태 변경 transaction이 commit된 뒤 구조 변경 event에서 호출한다. */
    @Override
    public void evict(long performanceTimeId) {
        try {
            Long result = redisTemplate.execute(
                    EVICT_SCRIPT,
                    List.of(
                            keyFactory.layoutGeneration(performanceTimeId),
                            keyFactory.availabilityGeneration(performanceTimeId),
                            keyFactory.layout(performanceTimeId),
                            keyFactory.availability(performanceTimeId),
                            keyFactory.legacySnapshot(performanceTimeId),
                            keyFactory.legacyRawSnapshot(performanceTimeId)
                    )
            );
            if (result == null) {
                throw new IllegalStateException("Redis split cache invalidation returned null");
            }
        } catch (RuntimeException exception) {
            throw failure("evict", performanceTimeId, exception);
        }
    }

    /** Redis 문자열 generation을 읽고 구성 요소별 cache 예외로 변환한다.
     * 연결 오류가 호출 경계 밖으로 누출되지 않도록 storage 책임을 이 메서드에 둔다. */
    private long readGeneration(String key, long performanceTimeId, String component) {
        try {
            return parseGeneration(redisTemplate.opsForValue().get(key), performanceTimeId, component);
        } catch (RuntimeException exception) {
            throw failure(component + "-generation-read", performanceTimeId, exception);
        }
    }

    /** Redis generation 문자열을 숫자로 변환한다.
     * 값이 손상된 경우 회차와 구성 요소를 포함한 예외를 만든다. */
    private long parseGeneration(String raw, long performanceTimeId, String component) {
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            throw failure(component + "-generation-deserialize", performanceTimeId, exception);
        }
    }

    /** Redis Hash 값에서 지정 필드의 문자열 표현을 꺼낸다.
     * 누락된 메타데이터는 호출자가 cache miss로 처리할 수 있도록 null을 반환한다. */
    private String value(Map<Object, Object> values, String field) {
        Object value = values.get(field);
        return value == null ? null : String.valueOf(value);
    }

    /** 상태와 좌석 version을 Redis Hash의 단일 문자열로 인코딩한다.
     * 구분자는 상태 enum 이름에 포함되지 않는 문자로 고정한다. */
    private String encodeAvailability(SeatAvailabilityCacheEntry entry) {
        return entry.seatStatus().name() + AVAILABILITY_SEPARATOR + entry.seatVersion();
    }

    /** Redis Hash 문자열을 동적 상태 record로 복원한다.
     * 형식이 손상되면 부분 결과를 반환하지 않고 cache 예외를 발생시킨다. */
    private SeatAvailabilityCacheEntry decodeAvailability(long seatId, String raw, long performanceTimeId) {
        int separator = raw.indexOf(AVAILABILITY_SEPARATOR);
        if (separator <= 0 || separator == raw.length() - 1) {
            throw failure(
                    "availability-deserialize",
                    performanceTimeId,
                    new IllegalArgumentException("Invalid availability value for seatId=" + seatId)
            );
        }
        try {
            return new SeatAvailabilityCacheEntry(
                    seatId,
                    org.example.ticket.util.constant.SeatStatus.valueOf(raw.substring(0, separator)),
                    Long.parseLong(raw.substring(separator + 1))
            );
        } catch (IllegalArgumentException exception) {
            throw failure("availability-deserialize", performanceTimeId, exception);
        }
    }

    /** storage 동작명과 회차를 포함한 cache 경계 예외를 만든다.
     * 호출자는 이 예외를 받아 DB fallback 또는 재구축 경로를 선택한다. */
    private SeatMapCacheException failure(String operation, long performanceTimeId, Exception cause) {
        return new SeatMapCacheException(
                "Seat map cache " + operation + " failed. performanceTimeId=" + performanceTimeId,
                cause
        );
    }
}
