package org.example.ticket.reservation.booking.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.booking.constant.ReservationErrorCode;
import org.example.ticket.reservation.booking.dto.response.SeatResponse;
import org.example.ticket.util.constant.SeatInfo;
import org.example.ticket.util.constant.SeatStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
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

    private SimpleMeterRegistry meterRegistry;
    private SeatMapCacheReader reader;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        reader = new SeatMapCacheReader(
                featurePolicy,
                properties,
                cacheStore,
                databaseReader,
                meterRegistry
        );
        lenient().when(properties.getCacheReadMaxConcurrency()).thenReturn(200);
        lenient().when(properties.getFallbackMaxConcurrency()).thenReturn(30);
        lenient().when(properties.getTtl()).thenReturn(Duration.ofMinutes(5));
        lenient().when(properties.isSingleFlightEnabled()).thenReturn(true);
        lenient().when(properties.getSingleFlightWaitTimeout()).thenReturn(Duration.ofSeconds(2));
    }

    @Test
    void cacheHitReturnsSnapshotWithoutOpeningDatabaseReader() {
        long performanceTimeId = 7L;
        SeatMapCacheEntry entry = entry(11L, SeatStatus.LOCKED);
        when(featurePolicy.appliesTo(performanceTimeId)).thenReturn(true);
        when(cacheStore.get(performanceTimeId))
                .thenReturn(Optional.of(new SeatMapCacheSnapshot(0L, List.of(entry))));

        List<SeatResponse> result = reader.read(performanceTimeId);

        assertThat(result).singleElement()
                .extracting(SeatResponse::getId, SeatResponse::getSeatStatus)
                .containsExactly(11L, SeatStatus.LOCKED);
        verify(databaseReader, never()).read(performanceTimeId);
        verify(cacheStore, never()).putIfVersionMatches(anyLong(), anyLong(), anyList(), any());
    }

    @Test
    void cacheMissReadsDatabaseAndStoresVersionedSnapshot() {
        long performanceTimeId = 7L;
        SeatResponse response = response(11L, SeatStatus.AVAILABLE);
        when(featurePolicy.appliesTo(performanceTimeId)).thenReturn(true);
        when(cacheStore.get(performanceTimeId)).thenReturn(Optional.empty());
        when(cacheStore.currentVersion(performanceTimeId)).thenReturn(0L);
        when(databaseReader.read(performanceTimeId)).thenReturn(List.of(response));
        when(cacheStore.putIfVersionMatches(
                eq(performanceTimeId),
                eq(0L),
                eq(List.of(SeatMapCacheEntry.from(response))),
                eq(Duration.ofMinutes(5))
        )).thenReturn(true);

        List<SeatResponse> result = reader.read(performanceTimeId);

        assertThat(result).containsExactly(response);
        verify(databaseReader).read(performanceTimeId);
        verify(cacheStore).putIfVersionMatches(
                performanceTimeId,
                0L,
                List.of(SeatMapCacheEntry.from(response)),
                Duration.ofMinutes(5)
        );
        assertThat(meterRegistry.counter(
                "imticket.seat-map-cache.events", "event", "singleflight_owner"
        ).count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter(
                "imticket.seat-map-cache.events", "event", "load"
        ).count()).isEqualTo(1.0);
    }

    @Test
    void concurrentColdMissesShareOneDatabaseProjection() throws Exception {
        long performanceTimeId = 7L;
        SeatResponse response = response(11L, SeatStatus.AVAILABLE);
        int requestCount = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch databaseEntered = new CountDownLatch(1);
        CountDownLatch releaseDatabase = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        when(featurePolicy.appliesTo(performanceTimeId)).thenReturn(true);
        when(cacheStore.get(performanceTimeId)).thenReturn(Optional.empty());
        when(cacheStore.currentVersion(performanceTimeId)).thenReturn(0L);
        when(databaseReader.read(performanceTimeId)).thenAnswer(invocation -> {
            databaseEntered.countDown();
            assertThat(releaseDatabase.await(2, TimeUnit.SECONDS)).isTrue();
            return List.of(response);
        });
        when(cacheStore.putIfVersionMatches(
                eq(performanceTimeId),
                eq(0L),
                eq(List.of(SeatMapCacheEntry.from(response))),
                eq(Duration.ofMinutes(5))
        )).thenReturn(true);

        List<Future<List<SeatResponse>>> joiners = new ArrayList<>();
        try {
            Future<List<SeatResponse>> owner = executor.submit(() -> reader.read(performanceTimeId));
            assertThat(databaseEntered.await(2, TimeUnit.SECONDS)).isTrue();

            for (int index = 0; index < requestCount - 1; index++) {
                joiners.add(executor.submit(() -> {
                    assertThat(start.await(2, TimeUnit.SECONDS)).isTrue();
                    return reader.read(performanceTimeId);
                }));
            }
            start.countDown();
            awaitCounter("singleflight_joined", requestCount - 1L);
            releaseDatabase.countDown();

            assertThat(owner.get(2, TimeUnit.SECONDS)).containsExactly(response);
            for (Future<List<SeatResponse>> future : joiners) {
                assertThat(future.get(2, TimeUnit.SECONDS)).containsExactly(response);
            }
        } finally {
            releaseDatabase.countDown();
            executor.shutdownNow();
        }

        verify(databaseReader).read(performanceTimeId);
        verify(cacheStore).putIfVersionMatches(
                performanceTimeId,
                0L,
                List.of(SeatMapCacheEntry.from(response)),
                Duration.ofMinutes(5)
        );
        assertThat(meterRegistry.counter(
                "imticket.seat-map-cache.events", "event", "singleflight_owner"
        ).count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter(
                "imticket.seat-map-cache.events", "event", "singleflight_joined"
        ).count()).isEqualTo(requestCount - 1.0);
    }

    @Test
    void conditionalWriteRejectionStillReturnsDatabaseResult() {
        long performanceTimeId = 7L;
        SeatResponse response = response(11L, SeatStatus.AVAILABLE);
        when(featurePolicy.appliesTo(performanceTimeId)).thenReturn(true);
        when(cacheStore.get(performanceTimeId)).thenReturn(Optional.empty());
        when(cacheStore.currentVersion(performanceTimeId)).thenReturn(0L);
        when(databaseReader.read(performanceTimeId)).thenReturn(List.of(response));
        when(cacheStore.putIfVersionMatches(
                eq(performanceTimeId),
                eq(0L),
                eq(List.of(SeatMapCacheEntry.from(response))),
                eq(Duration.ofMinutes(5))
        )).thenReturn(false);

        assertThat(reader.read(performanceTimeId)).containsExactly(response);

        assertThat(meterRegistry.counter(
                "imticket.seat-map-cache.events", "event", "conditional_write_rejected"
        ).count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter(
                "imticket.seat-map-cache.events", "event", "load"
        ).count()).isEqualTo(0.0);
    }

    @Test
    void ownerFailureIsPropagatedToJoinerAndInFlightEntryIsCleared() throws Exception {
        long performanceTimeId = 7L;
        RuntimeException databaseFailure = new IllegalStateException("database down");
        when(featurePolicy.appliesTo(performanceTimeId)).thenReturn(true);
        when(cacheStore.get(performanceTimeId)).thenReturn(Optional.empty());
        when(cacheStore.currentVersion(performanceTimeId)).thenReturn(0L);
        when(databaseReader.read(performanceTimeId)).thenThrow(databaseFailure);

        assertThatThrownBy(() -> reader.read(performanceTimeId))
                .isSameAs(databaseFailure);

        clearInvocations(databaseReader, cacheStore);
        when(cacheStore.get(performanceTimeId)).thenReturn(Optional.empty());
        when(cacheStore.currentVersion(performanceTimeId)).thenReturn(0L);
        doReturn(List.of(response(11L, SeatStatus.AVAILABLE))
        ).when(databaseReader).read(performanceTimeId);
        when(cacheStore.putIfVersionMatches(anyLong(), anyLong(), anyList(), any())).thenReturn(true);
        assertThat(reader.read(performanceTimeId)).hasSize(1);
        verify(databaseReader).read(performanceTimeId);
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
        verify(cacheStore, never()).putIfVersionMatches(anyLong(), anyLong(), anyList(), any());
    }

    @Test
    void redisReadFailureFallsBackToDatabase() {
        long performanceTimeId = 7L;
        List<SeatResponse> responses = List.of(response(11L, SeatStatus.AVAILABLE));
        when(featurePolicy.appliesTo(performanceTimeId)).thenReturn(true);
        when(cacheStore.get(performanceTimeId))
                .thenThrow(new SeatMapCacheException("redis down", new IllegalStateException()));
        when(databaseReader.read(performanceTimeId)).thenReturn(responses);

        assertThat(reader.read(performanceTimeId)).isEqualTo(responses);

        verify(databaseReader).read(performanceTimeId);
        verify(cacheStore, never()).putIfVersionMatches(anyLong(), anyLong(), anyList(), any());
    }

    @Test
    void rejectsDatabaseFallbackWhenSingleFlightIsDisabled() throws Exception {
        long performanceTimeId = 7L;
        SeatResponse response = response(11L, SeatStatus.AVAILABLE);
        CountDownLatch databaseEntered = new CountDownLatch(1);
        CountDownLatch releaseDatabase = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        when(properties.isSingleFlightEnabled()).thenReturn(false);
        when(properties.getFallbackMaxConcurrency()).thenReturn(1);
        when(featurePolicy.appliesTo(performanceTimeId)).thenReturn(true);
        when(cacheStore.get(performanceTimeId)).thenReturn(Optional.empty());
        when(cacheStore.currentVersion(performanceTimeId)).thenReturn(0L);
        when(databaseReader.read(performanceTimeId)).thenAnswer(invocation -> {
            databaseEntered.countDown();
            releaseDatabase.await(2, TimeUnit.SECONDS);
            return List.of(response);
        });

        try {
            Future<List<SeatResponse>> first = executor.submit(() -> reader.read(performanceTimeId));
            assertThat(databaseEntered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> reader.read(performanceTimeId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(exception -> ((BusinessException) exception).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SEAT_MAP_FALLBACK_OVER_CAPACITY);

            releaseDatabase.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).containsExactly(response);
        } finally {
            releaseDatabase.countDown();
            executor.shutdownNow();
        }
    }

    private static SeatMapCacheEntry entry(Long id, SeatStatus status) {
        return new SeatMapCacheEntry(id, 1, "A", 1, 1, SeatInfo.VIP, 10000, false, status);
    }

    private void awaitCounter(String event, double expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (meterRegistry.counter(
                "imticket.seat-map-cache.events", "event", event
        ).count() < expected) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for metric event=" + event);
            }
            Thread.sleep(5L);
        }
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
