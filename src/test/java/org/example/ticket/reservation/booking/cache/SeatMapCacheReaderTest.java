package org.example.ticket.reservation.booking.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.ticket.reservation.booking.dto.response.SeatResponse;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatMapCacheReaderTest {

    @Mock
    private SeatMapCacheFeaturePolicy featurePolicy;

    @Mock
    private SeatMapCacheProperties properties;

    @Mock
    private SeatMapCacheStore cacheStore;

    @Mock
    private SeatMapDatabaseReader databaseReader;

    private SeatMapCacheReader reader;

    @BeforeEach
    void setUp() {
        reader = new SeatMapCacheReader(
                featurePolicy,
                properties,
                cacheStore,
                databaseReader,
                new SimpleMeterRegistry()
        );
    }

    @Test
    void cacheHitReturnsSnapshotWithoutOpeningDatabaseReader() {
        long performanceTimeId = 7L;
        SeatMapCacheEntry entry = entry(11L, SeatStatus.LOCKED);
        when(featurePolicy.appliesTo(performanceTimeId)).thenReturn(true);
        when(cacheStore.get(performanceTimeId)).thenReturn(Optional.of(List.of(entry)));

        List<SeatResponse> result = reader.read(performanceTimeId);

        assertThat(result).singleElement()
                .extracting(SeatResponse::getId, SeatResponse::getSeatStatus)
                .containsExactly(11L, SeatStatus.LOCKED);
        verify(databaseReader, never()).read(performanceTimeId);
        verify(cacheStore, never()).put(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cacheMissReadsDatabaseAndStoresSnapshot() {
        long performanceTimeId = 7L;
        SeatResponse response = response(11L, SeatStatus.AVAILABLE);
        when(featurePolicy.appliesTo(performanceTimeId)).thenReturn(true);
        when(cacheStore.get(performanceTimeId)).thenReturn(Optional.empty());
        when(databaseReader.read(performanceTimeId)).thenReturn(List.of(response));
        when(properties.getTtl()).thenReturn(Duration.ofMinutes(5));

        List<SeatResponse> result = reader.read(performanceTimeId);

        assertThat(result).containsExactly(response);
        verify(databaseReader).read(performanceTimeId);
        verify(cacheStore).put(
                performanceTimeId,
                List.of(SeatMapCacheEntry.from(response)),
                Duration.ofMinutes(5)
        );
    }

    @Test
    void disabledFeatureUsesDatabaseWithoutTouchingCache() {
        long performanceTimeId = 7L;
        List<SeatResponse> responses = List.of(response(11L, SeatStatus.AVAILABLE));
        when(featurePolicy.appliesTo(performanceTimeId)).thenReturn(false);
        when(databaseReader.read(performanceTimeId)).thenReturn(responses);

        assertThat(reader.read(performanceTimeId)).isEqualTo(responses);

        verify(databaseReader).read(performanceTimeId);
        verify(cacheStore, never()).get(performanceTimeId);
        verify(cacheStore, never()).put(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void redisReadFailureFallsBackToDatabase() {
        long performanceTimeId = 7L;
        List<SeatResponse> responses = List.of(response(11L, SeatStatus.AVAILABLE));
        when(featurePolicy.appliesTo(performanceTimeId)).thenReturn(true);
        when(cacheStore.get(performanceTimeId))
                .thenThrow(new SeatMapCacheException("redis down", new IllegalStateException()));
        when(databaseReader.read(performanceTimeId)).thenReturn(responses);
        when(properties.getTtl()).thenReturn(Duration.ofMinutes(5));

        assertThat(reader.read(performanceTimeId)).isEqualTo(responses);

        verify(databaseReader).read(performanceTimeId);
        verify(cacheStore).put(
                performanceTimeId,
                List.of(SeatMapCacheEntry.from(responses.getFirst())),
                Duration.ofMinutes(5)
        );
    }

    private static SeatMapCacheEntry entry(Long id, SeatStatus status) {
        return new SeatMapCacheEntry(id, 1, "A", 1, 1, SeatInfo.VIP, 10000, false, status);
    }

    private static SeatResponse response(Long id, SeatStatus status) {
        return SeatResponse.builder()
                .id(id)
                .seatFloor(1)
                .seatSection("A")
                .seatRow(1)
                .seatNumber(1)
                .seatType(SeatInfo.VIP)
                .price(10000)
                .isReservation(false)
                .seatStatus(status)
                .build();
    }
}
