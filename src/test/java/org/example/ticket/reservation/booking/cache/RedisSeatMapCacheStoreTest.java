package org.example.ticket.reservation.booking.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisSeatMapCacheStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private ObjectMapper objectMapper;
    private SeatMapCacheKeyFactory keyFactory;
    private RedisSeatMapCacheStore store;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        keyFactory = new SeatMapCacheKeyFactory();
        store = new RedisSeatMapCacheStore(redisTemplate, objectMapper, keyFactory);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    void readsLayoutAndAvailabilityOnlyWhenBothGenerationsAndSeatSetMatch() throws Exception {
        long performanceTimeId = 7L;
        SeatLayoutCacheEntry layout = layout(11L);
        String payload = objectMapper.writeValueAsString(new SeatLayoutCachePayload(3L, List.of(layout)));
        when(valueOperations.multiGet(List.of(
                keyFactory.layoutGeneration(performanceTimeId),
                keyFactory.layout(performanceTimeId),
                keyFactory.availabilityGeneration(performanceTimeId)
        ))).thenReturn(Arrays.asList("3", payload, "5"));
        when(hashOperations.entries(keyFactory.availability(performanceTimeId))).thenReturn(Map.of(
                "__generation", "5",
                "__seat_count", "1",
                "11", "AVAILABLE|7"
        ));

        assertThat(store.get(performanceTimeId)).contains(
                new SeatMapCacheParts(
                        3L,
                        List.of(layout),
                        5L,
                        Map.of(11L, new SeatAvailabilityCacheEntry(11L, SeatStatus.AVAILABLE, 7L))
                )
        );
    }

    @Test
    void generationMismatchReturnsEmpty() throws Exception {
        String payload = objectMapper.writeValueAsString(new SeatLayoutCachePayload(2L, List.of(layout(11L))));
        when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList("3", payload, "5"));
        when(hashOperations.entries(keyFactory.availability(7L))).thenReturn(Map.of(
                "__generation", "5", "__seat_count", "1", "11", "AVAILABLE|7"
        ));

        assertThat(store.get(7L)).isEmpty();
    }

    @Test
    void missingAvailabilityReturnsEmpty() throws Exception {
        String payload = objectMapper.writeValueAsString(new SeatLayoutCachePayload(0L, List.of(layout(11L))));
        when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList("0", payload, "0"));
        when(hashOperations.entries(keyFactory.availability(7L))).thenReturn(Map.of());

        assertThat(store.get(7L)).isEmpty();
    }

    @Test
    void malformedAvailabilityIsReportedAsCacheException() throws Exception {
        String payload = objectMapper.writeValueAsString(new SeatLayoutCachePayload(0L, List.of(layout(11L))));
        when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList("0", payload, "0"));
        when(hashOperations.entries(keyFactory.availability(7L))).thenReturn(Map.of(
                "__generation", "0", "__seat_count", "1", "11", "broken"
        ));

        assertThatThrownBy(() -> store.get(7L))
                .isInstanceOf(SeatMapCacheException.class)
                .hasMessageContaining("deserialize");
    }

    @Test
    void missingGenerationMeansZero() {
        when(valueOperations.get(keyFactory.layoutGeneration(7L))).thenReturn(null);
        when(valueOperations.get(keyFactory.availabilityGeneration(7L))).thenReturn(null);

        assertThat(store.currentLayoutGeneration(7L)).isZero();
        assertThat(store.currentAvailabilityGeneration(7L)).isZero();
    }

    @Test
    void fullWriteUsesBothGenerationKeysAndAvailabilityPairs() {
        doReturn(1L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(Object[].class)
        );

        assertThat(store.putIfGenerationsMatch(
                7L,
                3L,
                5L,
                List.of(layout(11L)),
                List.of(new SeatAvailabilityCacheEntry(11L, SeatStatus.AVAILABLE, 7L)),
                Duration.ofMinutes(5)
        )).isTrue();

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(
                        keyFactory.layoutGeneration(7L),
                        keyFactory.availabilityGeneration(7L),
                        keyFactory.layout(7L),
                        keyFactory.availability(7L)
                )),
                any(Object[].class)
        );
    }

    @Test
    void availabilityUpdateUsesSeatIdStatusAndVersionTriples() {
        doReturn(1L).when(redisTemplate).execute(
                any(RedisScript.class), anyList(), any(Object[].class)
        );

        assertThat(store.updateAvailability(
                7L,
                List.of(new SeatAvailabilityCacheEntry(11L, SeatStatus.LOCKED, 8L))
        )).isTrue();

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(keyFactory.availabilityGeneration(7L), keyFactory.availability(7L))),
                any(Object[].class)
        );
    }

    @Test
    void evictIncrementsBothGenerationsAndDeletesSplitAndLegacyKeys() {
        doReturn(4L).when(redisTemplate).execute(any(RedisScript.class), anyList());

        store.evict(7L);

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(
                        keyFactory.layoutGeneration(7L),
                        keyFactory.availabilityGeneration(7L),
                        keyFactory.layout(7L),
                        keyFactory.availability(7L),
                        keyFactory.legacySnapshot(7L),
                        keyFactory.legacyRawSnapshot(7L)
                ))
        );
    }

    private static SeatLayoutCacheEntry layout(Long id) {
        return new SeatLayoutCacheEntry(id, 1, "A", 1, 1, SeatInfo.VIP, 10000, false);
    }
}
