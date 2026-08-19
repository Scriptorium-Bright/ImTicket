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

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisSeatMapCacheStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisSeatMapCacheStore store;

    @BeforeEach
    void setUp() {
        store = new RedisSeatMapCacheStore(
                redisTemplate,
                new ObjectMapper(),
                new SeatMapCacheKeyFactory()
        );
    }

    @Test
    void storesAndReadsJsonSnapshotWithExpectedKeyAndTtl() {
        long performanceTimeId = 7L;
        Duration ttl = Duration.ofMinutes(5);
        List<SeatMapCacheEntry> entries = List.of(
                new SeatMapCacheEntry(11L, 1, "A", 1, 1, SeatInfo.VIP, 10000, false, SeatStatus.AVAILABLE)
        );
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        store.put(performanceTimeId, entries, ttl);
        org.mockito.ArgumentCaptor<String> payloadCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq("reservation:seat-map:{7}:snapshot"), payloadCaptor.capture(), eq(ttl));

        when(valueOperations.get("reservation:seat-map:{7}:snapshot")).thenReturn(payloadCaptor.getValue());

        Optional<List<SeatMapCacheEntry>> result = store.get(performanceTimeId);

        assertThat(result).contains(entries);
    }

    @Test
    void missingSnapshotReturnsEmptyOptional() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("reservation:seat-map:{7}:snapshot")).thenReturn(null);

        assertThat(store.get(7L)).isEmpty();
    }

    @Test
    void malformedSnapshotIsReportedAsCacheException() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("reservation:seat-map:{7}:snapshot")).thenReturn("not-json");

        assertThatThrownBy(() -> store.get(7L))
                .isInstanceOf(SeatMapCacheException.class)
                .hasMessageContaining("deserialize");
    }

    @Test
    void evictsOnlyTargetPerformanceTimeSnapshot() {
        store.evict(7L);

        verify(redisTemplate).delete("reservation:seat-map:{7}:snapshot");
    }
}
