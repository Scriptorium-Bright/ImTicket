package org.example.ticket.reservation.booking.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 Redis Lua script와 String·Hash 저장 형식을 검증하는 선택적 통합 시험이다. */
@EnabledIfEnvironmentVariable(named = "SEAT_MAP_CACHE_REDIS_TEST", matches = "(?i)true")
class RedisSeatMapCacheStoreIntegrationTest {

    private static final long PERFORMANCE_TIME_ID = 990000001L;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private SeatMapCacheKeyFactory keyFactory;
    private RedisSeatMapCacheStore store;

    /** 환경 변수로 지정한 Redis에 연결해 통합 시험을 준비한다. */
    @BeforeAll
    static void connectRedis() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("SEAT_MAP_CACHE_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("SEAT_MAP_CACHE_REDIS_PORT", "16380"))
        );
        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    /** 이전 시험의 key를 비우고 split store를 새로 만든다. */
    @BeforeEach
    void resetRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        keyFactory = new SeatMapCacheKeyFactory();
        store = new RedisSeatMapCacheStore(redisTemplate, new ObjectMapper(), keyFactory);
    }

    /** 시험이 끝나면 Lettuce connection factory를 정리한다. */
    @AfterAll
    static void closeRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    /** 전체 저장 뒤 String layout과 Hash availability를 일관된 읽기 모델로 복원한다. */
    @Test
    void writesAndReadsSplitModels() {
        assertThat(store.putIfGenerationsMatch(
                PERFORMANCE_TIME_ID,
                0L,
                0L,
                List.of(layout(11L), layout(12L)),
                List.of(availability(11L, SeatStatus.AVAILABLE, 7L), availability(12L, SeatStatus.AVAILABLE, 7L)),
                Duration.ofMinutes(5)
        )).isTrue();

        assertThat(store.get(PERFORMANCE_TIME_ID)).contains(
                new SeatMapCacheParts(
                        0L,
                        List.of(layout(11L), layout(12L)),
                        0L,
                        Map.of(
                                11L, availability(11L, SeatStatus.AVAILABLE, 7L),
                                12L, availability(12L, SeatStatus.AVAILABLE, 7L)
                        )
                )
        );
    }

    /** 최신 version 상태를 반영하고 늦게 도착한 과거 version을 유지한다. */
    @Test
    void partialUpdateHonorsSeatVersion() {
        store.putIfGenerationsMatch(
                PERFORMANCE_TIME_ID,
                0L,
                0L,
                List.of(layout(11L)),
                List.of(availability(11L, SeatStatus.AVAILABLE, 7L)),
                Duration.ofMinutes(5)
        );

        assertThat(store.updateAvailability(
                PERFORMANCE_TIME_ID,
                List.of(availability(11L, SeatStatus.LOCKED, 8L))
        )).isTrue();
        assertThat(store.updateAvailability(
                PERFORMANCE_TIME_ID,
                List.of(availability(11L, SeatStatus.AVAILABLE, 7L))
        )).isTrue();

        assertThat(store.get(PERFORMANCE_TIME_ID)).get()
                .extracting(parts -> parts.availabilityEntries().get(11L))
                .isEqualTo(availability(11L, SeatStatus.LOCKED, 8L));
    }

    /** live Hash가 없으면 상태 부분 갱신을 만들지 않고 false를 반환한다. */
    @Test
    void partialUpdateSkipsMissingAvailabilityHash() {
        assertThat(store.updateAvailability(
                PERFORMANCE_TIME_ID,
                List.of(availability(11L, SeatStatus.LOCKED, 8L))
        )).isFalse();
        assertThat(store.get(PERFORMANCE_TIME_ID)).isEmpty();
    }

    /** 구조 변경 event가 두 payload를 지우고 다음 generation을 만든다. */
    @Test
    void evictionRemovesBothModels() {
        store.putIfGenerationsMatch(
                PERFORMANCE_TIME_ID,
                0L,
                0L,
                List.of(layout(11L)),
                List.of(availability(11L, SeatStatus.AVAILABLE, 7L)),
                Duration.ofMinutes(5)
        );

        store.evict(PERFORMANCE_TIME_ID);

        assertThat(store.get(PERFORMANCE_TIME_ID)).isEmpty();
        assertThat(store.currentLayoutGeneration(PERFORMANCE_TIME_ID)).isEqualTo(1L);
        assertThat(store.currentAvailabilityGeneration(PERFORMANCE_TIME_ID)).isEqualTo(1L);
        assertThat(redisTemplate.hasKey(keyFactory.layout(PERFORMANCE_TIME_ID))).isFalse();
        assertThat(redisTemplate.hasKey(keyFactory.availability(PERFORMANCE_TIME_ID))).isFalse();
    }

    /** 통합 시험에 사용할 정적 좌석 배치 값을 만든다. */
    private static SeatLayoutCacheEntry layout(Long seatId) {
        return new SeatLayoutCacheEntry(seatId, 1, "A", 1, seatId.intValue(), SeatInfo.VIP, 10000, false);
    }

    /** 통합 시험에 사용할 동적 상태와 version 값을 만든다. */
    private static SeatAvailabilityCacheEntry availability(Long seatId, SeatStatus status, Long version) {
        return new SeatAvailabilityCacheEntry(seatId, status, version);
    }
}
