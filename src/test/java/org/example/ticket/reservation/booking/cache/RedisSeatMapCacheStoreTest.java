package org.example.ticket.reservation.booking.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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

    private ObjectMapper objectMapper;
    private SeatMapCacheKeyFactory keyFactory;
    private RedisSeatMapCacheStore store;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        keyFactory = new SeatMapCacheKeyFactory();
        store = new RedisSeatMapCacheStore(redisTemplate, objectMapper, keyFactory);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void readsVersionedJsonSnapshotOnlyWhenVersionMatches() throws Exception {
        long performanceTimeId = 7L;
        List<SeatMapCacheEntry> entries = List.of(
                new SeatMapCacheEntry(11L, 1, "A", 1, 1, SeatInfo.VIP, 10000, false, SeatStatus.AVAILABLE)
        );
        String payload = objectMapper.writeValueAsString(new SeatMapCacheSnapshot(3L, entries));
        when(valueOperations.multiGet(List.of(
                keyFactory.version(performanceTimeId),
                keyFactory.snapshot(performanceTimeId),
                keyFactory.legacySnapshot(performanceTimeId)
        ))).thenReturn(Arrays.asList("3", payload, null));

        Optional<SeatMapCacheSnapshot> result = store.get(performanceTimeId);

        assertThat(result).contains(new SeatMapCacheSnapshot(3L, entries));
    }

    @Test
    void versionMismatchReturnsEmptySnapshot() throws Exception {
        long performanceTimeId = 7L;
        List<SeatMapCacheEntry> entries = List.of(
                new SeatMapCacheEntry(11L, 1, "A", 1, 1, SeatInfo.VIP, 10000, false, SeatStatus.AVAILABLE)
        );
        String payload = objectMapper.writeValueAsString(new SeatMapCacheSnapshot(2L, entries));
        when(valueOperations.multiGet(List.of(
                keyFactory.version(performanceTimeId),
                keyFactory.snapshot(performanceTimeId),
                keyFactory.legacySnapshot(performanceTimeId)
        ))).thenReturn(Arrays.asList("3", payload, null));

        assertThat(store.get(performanceTimeId)).isEmpty();
    }

    @Test
    void missingSnapshotReturnsEmptyOptionalAndMissingVersionMeansZero() {
        when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList(null, null, null));

        assertThat(store.get(7L)).isEmpty();
        assertThat(store.currentVersion(7L)).isZero();
    }

    @Test
    void legacyRawListSnapshotIsReadableDuringMigration() throws Exception {
        long performanceTimeId = 7L;
        List<SeatMapCacheEntry> entries = List.of(
                new SeatMapCacheEntry(11L, 1, "A", 1, 1, SeatInfo.VIP, 10000, false, SeatStatus.AVAILABLE)
        );
        String payload = objectMapper.writeValueAsString(entries);
        when(valueOperations.multiGet(List.of(
                keyFactory.version(performanceTimeId),
                keyFactory.snapshot(performanceTimeId),
                keyFactory.legacySnapshot(performanceTimeId)
        ))).thenReturn(Arrays.asList("0", null, payload));

        assertThat(store.get(performanceTimeId))
                .contains(new SeatMapCacheSnapshot(0L, entries));
    }

    @Test
    void legacyRawListIsMissAfterVersionWasIncremented() throws Exception {
        String payload = objectMapper.writeValueAsString(List.of(
                new SeatMapCacheEntry(11L, 1, "A", 1, 1, SeatInfo.VIP, 10000, false, SeatStatus.AVAILABLE)
        ));
        when(valueOperations.multiGet(List.of(
                keyFactory.version(7L),
                keyFactory.snapshot(7L),
                keyFactory.legacySnapshot(7L)
        ))).thenReturn(Arrays.asList("1", null, payload));

        assertThat(store.get(7L)).isEmpty();
    }

    @Test
    void malformedSnapshotIsReportedAsCacheException() {
        when(valueOperations.multiGet(anyList())).thenReturn(Arrays.asList("0", "not-json", null));

        assertThatThrownBy(() -> store.get(7L))
                .isInstanceOf(SeatMapCacheException.class)
                .hasMessageContaining("deserialize");
    }

    @Test
    void conditionalWriteSerializesVersionAndDelegatesToRedisScript() {
        doReturn(1L).when(redisTemplate).execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        );
        List<SeatMapCacheEntry> entries = List.of(
                new SeatMapCacheEntry(11L, 1, "A", 1, 1, SeatInfo.VIP, 10000, false, SeatStatus.AVAILABLE)
        );

        assertThat(store.putIfVersionMatches(7L, 3L, entries, Duration.ofMinutes(5))).isTrue();

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(keyFactory.version(7L), keyFactory.snapshot(7L))),
                eq("3"),
                anyString(),
                eq("300000")
        );
    }

    @Test
    void conditionalWriteReturnsFalseWhenRedisRejectsVersion() {
        doReturn(0L).when(redisTemplate).execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        );

        assertThat(store.putIfVersionMatches(7L, 3L, List.of(), Duration.ofMinutes(5))).isFalse();
    }

    @Test
    void evictDelegatesVersionIncrementAndSnapshotDeletionToRedisScript() {
        doReturn(4L).when(redisTemplate).execute(
                any(RedisScript.class),
                anyList()
        );

        store.evict(7L);

        verify(redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of(
                        keyFactory.version(7L),
                        keyFactory.snapshot(7L),
                        keyFactory.legacySnapshot(7L)
                ))
        );
    }
}
