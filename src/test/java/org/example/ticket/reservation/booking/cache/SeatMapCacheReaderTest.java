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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    void cacheHitJoinsLayoutAndAvailabilityWithoutDatabase() {
        long performanceTimeId = 7L;
        when(featurePolicy.appliesTo(performanceTimeId)).thenReturn(true);
        when(cacheStore.get(performanceTimeId)).thenReturn(Optional.of(parts(11L, SeatStatus.LOCKED)));

        List<SeatResponse> result = reader.read(performanceTimeId);

        assertThat(result).singleElement()
                .extracting(SeatResponse::getId, SeatResponse::getSeatStatus)
                .containsExactly(11L, SeatStatus.LOCKED);
        verify(databaseReader, never()).read(performanceTimeId);
        verify(databaseReader, never()).readSplit(performanceTimeId);
        verify(cacheStore, never()).putIfGenerationsMatch(anyLong(), anyLong(), anyLong(), anyList(), anyList(), any());
    }

    @Test
    void coldMissReadsSplitProjectionAndStoresBothModels() {
        long performanceTimeId = 7L;
        SeatMapDatabaseSnapshot databaseSnapshot = databaseSnapshot(11L, SeatStatus.AVAILABLE);
        when(featurePolicy.appliesTo(performanceTimeId)).thenReturn(true);
        when(cacheStore.get(performanceTimeId)).thenReturn(Optional.empty());
        when(cacheStore.currentLayoutGeneration(performanceTimeId)).thenReturn(3L);
        when(cacheStore.currentAvailabilityGeneration(performanceTimeId)).thenReturn(5L);
        when(databaseReader.readSplit(performanceTimeId)).thenReturn(databaseSnapshot);
        when(cacheStore.putIfGenerationsMatch(
                eq(performanceTimeId), eq(3L), eq(5L),
                eq(databaseSnapshot.layoutEntries()), eq(databaseSnapshot.availabilityEntries()),
                eq(Duration.ofMinutes(5))
        )).thenReturn(true);

        assertThat(reader.read(performanceTimeId)).hasSize(1);

        verify(databaseReader).readSplit(performanceTimeId);
        verify(cacheStore).putIfGenerationsMatch(
                performanceTimeId,
                3L,
                5L,
                databaseSnapshot.layoutEntries(),
                databaseSnapshot.availabilityEntries(),
                Duration.ofMinutes(5)
        );
        assertThat(counter("singleflight_owner")).isEqualTo(1.0);
        assertThat(counter("load")).isEqualTo(1.0);
    }

    @Test
    void concurrentColdMissesShareOneSplitProjection() throws Exception {
        long performanceTimeId = 7L;
        SeatMapDatabaseSnapshot databaseSnapshot = databaseSnapshot(11L, SeatStatus.AVAILABLE);
        int requestCount = 20;
        CountDownLatch databaseEntered = new CountDownLatch(1);
        CountDownLatch releaseDatabase = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        when(featurePolicy.appliesTo(performanceTimeId)).thenReturn(true);
        when(cacheStore.get(performanceTimeId)).thenReturn(Optional.empty());
        when(cacheStore.currentLayoutGeneration(performanceTimeId)).thenReturn(0L);
        when(cacheStore.currentAvailabilityGeneration(performanceTimeId)).thenReturn(0L);
        when(databaseReader.readSplit(performanceTimeId)).thenAnswer(invocation -> {
            databaseEntered.countDown();
            assertThat(releaseDatabase.await(2, TimeUnit.SECONDS)).isTrue();
            return databaseSnapshot;
        });
        when(cacheStore.putIfGenerationsMatch(anyLong(), anyLong(), anyLong(), anyList(), anyList(), any()))
                .thenReturn(true);

        List<Future<List<SeatResponse>>> joiners = new ArrayList<>();
        try {
            Future<List<SeatResponse>> owner = executor.submit(() -> reader.read(performanceTimeId));
            assertThat(databaseEntered.await(2, TimeUnit.SECONDS)).isTrue();
            for (int index = 0; index < requestCount - 1; index++) {
                joiners.add(executor.submit(() -> reader.read(performanceTimeId)));
            }
            awaitCounter("singleflight_joined", requestCount - 1L);
            releaseDatabase.countDown();

            assertThat(owner.get(2, TimeUnit.SECONDS)).hasSize(1);
            for (Future<List<SeatResponse>> future : joiners) {
                assertThat(future.get(2, TimeUnit.SECONDS)).hasSize(1);
            }
        } finally {
            releaseDatabase.countDown();
            executor.shutdownNow();
        }

        verify(databaseReader).readSplit(performanceTimeId);
        assertThat(counter("singleflight_owner")).isEqualTo(1.0);
        assertThat(counter("singleflight_joined")).isEqualTo(requestCount - 1.0);
    }

    /**
     * single-flight map은 SeatMapCacheReader 인스턴스(JVM) 로컬 상태이므로
     * 서로 다른 application instance를 모사한 두 Reader의 cold miss는 각각 DB rebuild를 시작한다.
     * 이 테스트는 distributed single-flight가 현재 보장 범위 밖임을 의도적으로 증명한다.
     */
    @Test
    void separateReaderInstancesCanEachOwnTheSameColdMiss() throws Exception {
        long performanceTimeId = 7L;
        SeatMapDatabaseSnapshot databaseSnapshot = databaseSnapshot(11L, SeatStatus.AVAILABLE);
        CountDownLatch bothDatabaseReadsEntered = new CountDownLatch(2);
        CountDownLatch releaseDatabase = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        when(featurePolicy.appliesTo(performanceTimeId)).thenReturn(true);
        when(cacheStore.get(performanceTimeId)).thenReturn(Optional.empty());
        when(cacheStore.currentLayoutGeneration(performanceTimeId)).thenReturn(0L);
        when(cacheStore.currentAvailabilityGeneration(performanceTimeId)).thenReturn(0L);
        when(databaseReader.readSplit(performanceTimeId)).thenAnswer(invocation -> {
            bothDatabaseReadsEntered.countDown();
            assertThat(releaseDatabase.await(2, TimeUnit.SECONDS)).isTrue();
            return databaseSnapshot;
        });
        when(cacheStore.putIfGenerationsMatch(anyLong(), anyLong(), anyLong(), anyList(), anyList(), any()))
                .thenReturn(true);

        SimpleMeterRegistry secondMeterRegistry = new SimpleMeterRegistry();
        SeatMapCacheReader secondReader = new SeatMapCacheReader(
                featurePolicy,
                properties,
                cacheStore,
                databaseReader,
                secondMeterRegistry
        );

        try {
            Future<List<SeatResponse>> first = executor.submit(() -> reader.read(performanceTimeId));
            Future<List<SeatResponse>> second = executor.submit(() -> secondReader.read(performanceTimeId));

            assertThat(bothDatabaseReadsEntered.await(2, TimeUnit.SECONDS)).isTrue();
            releaseDatabase.countDown();

            assertThat(first.get(2, TimeUnit.SECONDS)).hasSize(1);
            assertThat(second.get(2, TimeUnit.SECONDS)).hasSize(1);
        } finally {
            releaseDatabase.countDown();
            executor.shutdownNow();
        }

        verify(databaseReader, times(2)).readSplit(performanceTimeId);
        assertThat(counter("singleflight_owner")).isEqualTo(1.0);
        assertThat(secondMeterRegistry.counter(
                "imticket.seat-map-cache.events",
                "event", "singleflight_owner"
        ).count()).isEqualTo(1.0);
    }

    @Test
    void conditionalWriteRejectionStillReturnsDatabaseResult() {
        long performanceTimeId = 7L;
        SeatMapDatabaseSnapshot databaseSnapshot = databaseSnapshot(11L, SeatStatus.AVAILABLE);
        when(featurePolicy.appliesTo(performanceTimeId)).thenReturn(true);
        when(cacheStore.get(performanceTimeId)).thenReturn(Optional.empty());
        when(cacheStore.currentLayoutGeneration(performanceTimeId)).thenReturn(0L);
        when(cacheStore.currentAvailabilityGeneration(performanceTimeId)).thenReturn(0L);
        when(databaseReader.readSplit(performanceTimeId)).thenReturn(databaseSnapshot);
        when(cacheStore.putIfGenerationsMatch(anyLong(), anyLong(), anyLong(), anyList(), anyList(), any()))
                .thenReturn(false);

        assertThat(reader.read(performanceTimeId)).hasSize(1);
        assertThat(counter("conditional_write_rejected")).isEqualTo(1.0);
    }

    @Test
    void ownerFailureClearsInFlightEntry() {
        long performanceTimeId = 7L;
        RuntimeException databaseFailure = new IllegalStateException("database down");
        when(featurePolicy.appliesTo(performanceTimeId)).thenReturn(true);
        when(cacheStore.get(performanceTimeId)).thenReturn(Optional.empty());
        when(cacheStore.currentLayoutGeneration(performanceTimeId)).thenReturn(0L);
        when(cacheStore.currentAvailabilityGeneration(performanceTimeId)).thenReturn(0L);
        when(databaseReader.readSplit(performanceTimeId)).thenThrow(databaseFailure);

        assertThatThrownBy(() -> reader.read(performanceTimeId)).isSameAs(databaseFailure);

        clearInvocations(databaseReader, cacheStore);
        when(cacheStore.get(performanceTimeId)).thenReturn(Optional.empty());
        when(cacheStore.currentLayoutGeneration(performanceTimeId)).thenReturn(0L);
        when(cacheStore.currentAvailabilityGeneration(performanceTimeId)).thenReturn(0L);
        doReturn(databaseSnapshot(11L, SeatStatus.AVAILABLE)).when(databaseReader).readSplit(performanceTimeId);
        when(cacheStore.putIfGenerationsMatch(anyLong(), anyLong(), anyLong(), anyList(), anyList(), any()))
                .thenReturn(true);

        assertThat(reader.read(performanceTimeId)).hasSize(1);
        verify(databaseReader).readSplit(performanceTimeId);
    }

    @Test
    void disabledFeatureUsesExistingDatabaseProjection() {
        long performanceTimeId = 7L;
        List<SeatResponse> responses = List.of(response(11L, SeatStatus.AVAILABLE));
        when(featurePolicy.appliesTo(performanceTimeId)).thenReturn(false);
        when(databaseReader.read(performanceTimeId)).thenReturn(responses);

        assertThat(reader.read(performanceTimeId)).isEqualTo(responses);
        verify(databaseReader).read(performanceTimeId);
        verify(cacheStore, never()).get(performanceTimeId);
    }

    @Test
    void redisReadFailureFallsBackToDatabaseSplitProjection() {
        long performanceTimeId = 7L;
        SeatMapDatabaseSnapshot databaseSnapshot = databaseSnapshot(11L, SeatStatus.AVAILABLE);
        when(featurePolicy.appliesTo(performanceTimeId)).thenReturn(true);
        when(cacheStore.get(performanceTimeId))
                .thenThrow(new SeatMapCacheException("redis down", new IllegalStateException()));
        when(databaseReader.readSplit(performanceTimeId)).thenReturn(databaseSnapshot);

        assertThat(reader.read(performanceTimeId)).hasSize(1);
        verify(databaseReader).readSplit(performanceTimeId);
        verify(cacheStore, never()).putIfGenerationsMatch(anyLong(), anyLong(), anyLong(), anyList(), anyList(), any());
    }

    @Test
    void rejectsDatabaseFallbackWhenSingleFlightIsDisabled() throws Exception {
        long performanceTimeId = 7L;
        CountDownLatch databaseEntered = new CountDownLatch(1);
        CountDownLatch releaseDatabase = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        when(properties.isSingleFlightEnabled()).thenReturn(false);
        when(properties.getFallbackMaxConcurrency()).thenReturn(1);
        when(featurePolicy.appliesTo(performanceTimeId)).thenReturn(true);
        when(cacheStore.get(performanceTimeId)).thenReturn(Optional.empty());
        when(cacheStore.currentLayoutGeneration(performanceTimeId)).thenReturn(0L);
        when(cacheStore.currentAvailabilityGeneration(performanceTimeId)).thenReturn(0L);
        when(databaseReader.readSplit(performanceTimeId)).thenAnswer(invocation -> {
            databaseEntered.countDown();
            releaseDatabase.await(2, TimeUnit.SECONDS);
            return databaseSnapshot(11L, SeatStatus.AVAILABLE);
        });

        try {
            Future<List<SeatResponse>> first = executor.submit(() -> reader.read(performanceTimeId));
            assertThat(databaseEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> reader.read(performanceTimeId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(exception -> ((BusinessException) exception).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SEAT_MAP_FALLBACK_OVER_CAPACITY);
            releaseDatabase.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).hasSize(1);
        } finally {
            releaseDatabase.countDown();
            executor.shutdownNow();
        }
    }

    private double counter(String event) {
        return meterRegistry.counter("imticket.seat-map-cache.events", "event", event).count();
    }

    private void awaitCounter(String event, double expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (counter(event) < expected) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for metric event=" + event);
            }
            Thread.sleep(5L);
        }
    }

    private static SeatMapCacheParts parts(Long id, SeatStatus status) {
        return new SeatMapCacheParts(
                3L,
                List.of(new SeatLayoutCacheEntry(id, 1, "A", 1, 1, SeatInfo.VIP, 10000, false)),
                5L,
                Map.of(id, new SeatAvailabilityCacheEntry(id, status, 7L))
        );
    }

    private static SeatMapDatabaseSnapshot databaseSnapshot(Long id, SeatStatus status) {
        return new SeatMapDatabaseSnapshot(
                List.of(new SeatLayoutCacheEntry(id, 1, "A", 1, 1, SeatInfo.VIP, 10000, false)),
                List.of(new SeatAvailabilityCacheEntry(id, status, 7L))
        );
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
