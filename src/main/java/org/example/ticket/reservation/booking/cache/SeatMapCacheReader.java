package org.example.ticket.reservation.booking.cache;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.common.exception.BusinessException;
import org.example.ticket.reservation.booking.constant.ReservationErrorCode;
import org.example.ticket.reservation.booking.dto.response.SeatResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 좌석 조회의 cache hit·miss·single-flight·fallback 정책을 조정한다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SeatMapCacheReader {

    private final SeatMapCacheFeaturePolicy featurePolicy;
    private final SeatMapCacheProperties properties;
    private final SeatMapCacheStore cacheStore;
    private final SeatMapDatabaseReader databaseReader;
    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<Long, Semaphore> fallbackPermits = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, CacheReadGate> cacheReadGates = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, CompletableFuture<List<SeatResponse>>> inFlightLoads = new ConcurrentHashMap<>();

    /**
     * commit 이후 snapshot이 삭제되는 시점에 Redis 읽기 gate를 새 세대로 교체한다.
     * 다음 조회가 확장된 hit 동시성으로 오래된 snapshot을 대량으로 읽지 않도록 한다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void resetReadGateAfterInvalidation(SeatMapInvalidationEvent event) {
        cacheReadGates.remove(event.performanceTimeId());
    }

    /**
     * feature flag에 따라 cache hit·miss·single-flight·DB fallback 경로를 선택한다.
     * cache 오류는 좌석 조회 자체의 실패로 전파하지 않고 DB 경로로 전환한다.
     */
    public List<SeatResponse> read(long performanceTimeId) {
        if (!featurePolicy.appliesTo(performanceTimeId)) {
            count("disabled");
            return readDatabaseDirect(performanceTimeId);
        }

        if (properties.isSingleFlightEnabled()) {
            return readWithSingleFlight(performanceTimeId);
        }

        return readWithoutSingleFlight(performanceTimeId);
    }

    /**
     * cache 조회 후 miss만 single-flight 재구축으로 합친다.
     * 이미 owner가 있으면 Redis를 다시 조회하지 않고 바로 owner future를 기다린다.
     */
    private List<SeatResponse> readWithSingleFlight(long performanceTimeId) {
        CompletableFuture<List<SeatResponse>> existing = inFlightLoads.get(performanceTimeId);
        if (existing != null) {
            count("singleflight_joined");
            return awaitOwner(performanceTimeId, existing);
        }

        CacheReadGate cacheReadGate = cacheReadGates.computeIfAbsent(
                performanceTimeId,
                ignored -> new CacheReadGate(1)
        );
        if (!cacheReadGate.tryAcquire()) {
            count("singleflight_gate_bypass");
            return readThroughSingleFlight(performanceTimeId, false);
        }

        Optional<SeatMapCacheParts> cached;
        boolean cacheReadFailed = false;
        try {
            cached = cacheStore.get(performanceTimeId);
            cacheReadGate.markSuccessfulRead(cached.isPresent());
        } catch (SeatMapCacheException exception) {
            count("fallback");
            log.warn(
                    "Seat map cache read failed; falling back to database. performanceTimeId={}, reason={}",
                    performanceTimeId,
                    exception.getMessage()
            );
            cacheReadFailed = true;
            cached = Optional.empty();
        } finally {
            cacheReadGate.release();
        }

        if (cached.isPresent()) {
            count("hit");
            return toResponses(cached.get());
        }
        count("miss");
        if (cacheReadGate.isExpanded()) {
            cacheReadGates.remove(performanceTimeId, cacheReadGate);
        }
        return readThroughSingleFlight(performanceTimeId, cacheReadFailed);
    }

    /**
     * 동일 회차의 동시 miss를 하나의 future에 합친다.
     * future는 DB 결과와 예외를 owner와 joiner 사이에 전달하는 in-flight 결과 슬롯이다.
     */
    private List<SeatResponse> readThroughSingleFlight(long performanceTimeId, boolean cacheReadFailed) {
        CompletableFuture<List<SeatResponse>> candidate = new CompletableFuture<>();
        CompletableFuture<List<SeatResponse>> existing = inFlightLoads.putIfAbsent(performanceTimeId, candidate);
        if (existing != null) {
            count("singleflight_joined");
            return awaitOwner(performanceTimeId, existing);
        }

        count("singleflight_owner");
        try {
            List<SeatResponse> responses = loadSnapshot(performanceTimeId, cacheReadFailed);
            candidate.complete(responses);
            return responses;
        } catch (Throwable throwable) {
            candidate.completeExceptionally(throwable);
            throw propagate(throwable);
        } finally {
            inFlightLoads.remove(performanceTimeId, candidate);
        }
    }

    /**
     * single-flight가 꺼진 호환 경로를 유지한다.
     * 기존 cache read gate와 fallback 동시성 상한은 이 경로에서만 사용한다.
     */
    private List<SeatResponse> readWithoutSingleFlight(long performanceTimeId) {

        CacheReadGate cacheReadGate = cacheReadGates.computeIfAbsent(
                performanceTimeId,
                ignored -> new CacheReadGate(properties.getCacheReadMaxConcurrency())
        );
        if (!cacheReadGate.tryAcquire()) {
            count("fallback");
            return readDatabaseWithFallbackBudget(performanceTimeId);
        }

        Optional<SeatMapCacheParts> cached;
        boolean cacheReadFailed = false;
        try {
            cached = cacheStore.get(performanceTimeId);
            cacheReadGate.markSuccessfulRead(cached.isPresent());
        } catch (SeatMapCacheException exception) {
            count("fallback");
            log.warn(
                    "Seat map cache read failed; falling back to database. performanceTimeId={}, reason={}",
                    performanceTimeId,
                    exception.getMessage(),
                    exception
            );
            cacheReadFailed = true;
            cached = Optional.empty();
        } finally {
            cacheReadGate.release();
        }

        if (cached.isPresent()) {
            count("hit");
            return toResponses(cached.get());
        }
        count("miss");
        if (cacheReadGate.isExpanded()) {
            cacheReadGates.remove(performanceTimeId, cacheReadGate);
        }
        return loadSnapshot(performanceTimeId, cacheReadFailed);
    }

    /**
     * owner 결과를 제한된 시간 동안 기다린다.
     * joiner timeout은 DB fallback을 새로 시작하지 않고 기존 503 계약으로 종료한다.
     */
    private List<SeatResponse> awaitOwner(
            long performanceTimeId,
            CompletableFuture<List<SeatResponse>> ownerFuture
    ) {
        long waitMillis = Math.max(1L, properties.getSingleFlightWaitTimeout().toMillis());
        try {
            return ownerFuture.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            count("singleflight_timeout");
            throw new BusinessException(ReservationErrorCode.SEAT_MAP_FALLBACK_OVER_CAPACITY, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            count("singleflight_interrupted");
            throw new BusinessException(ReservationErrorCode.SEAT_MAP_FALLBACK_OVER_CAPACITY, exception);
        } catch (ExecutionException exception) {
            count("singleflight_failure");
            throw propagate(exception.getCause());
        } catch (CancellationException exception) {
            count("singleflight_failure");
            throw new BusinessException(ReservationErrorCode.SEAT_MAP_FALLBACK_OVER_CAPACITY, exception);
        }
    }

    /**
     * owner 또는 single-flight 비활성 경로의 snapshot 재구축을 수행한다.
     * cache 재확인으로 owner 등록 직전 완료된 재구축을 재사용한다.
     */
    private List<SeatResponse> loadSnapshot(long performanceTimeId, boolean cacheReadFailed) {
        boolean cacheWritable = !cacheReadFailed;
        if (cacheWritable) {
            try {
                Optional<SeatMapCacheParts> rechecked = cacheStore.get(performanceTimeId);
                if (rechecked.isPresent()) {
                    count("singleflight_recheck_hit");
                    return toResponses(rechecked.get());
                }
            } catch (SeatMapCacheException exception) {
                cacheWritable = false;
                count("cache_error");
                log.warn(
                        "Seat map cache recheck failed; returning database result. performanceTimeId={}, reason={}",
                        performanceTimeId,
                        exception.getMessage()
                );
            }
        }

        long expectedLayoutGeneration = 0L;
        long expectedAvailabilityGeneration = 0L;
        if (cacheWritable) {
            try {
                expectedLayoutGeneration = cacheStore.currentLayoutGeneration(performanceTimeId);
                expectedAvailabilityGeneration = cacheStore.currentAvailabilityGeneration(performanceTimeId);
            } catch (SeatMapCacheException exception) {
                cacheWritable = false;
                count("version_read_failure");
                log.warn(
                        "Seat map cache version read failed; returning database result. performanceTimeId={}, reason={}",
                        performanceTimeId,
                        exception.getMessage()
                );
            }
        }

        SeatMapDatabaseSnapshot databaseSnapshot = readDatabaseSnapshotWithFallbackBudget(performanceTimeId);
        List<SeatResponse> responses = toResponses(databaseSnapshot);
        if (!cacheWritable) {
            return responses;
        }

        try {
            boolean stored = cacheStore.putIfGenerationsMatch(
                    performanceTimeId,
                    expectedLayoutGeneration,
                    expectedAvailabilityGeneration,
                    databaseSnapshot.layoutEntries(),
                    databaseSnapshot.availabilityEntries(),
                    properties.getTtl()
            );
            count(stored ? "load" : "conditional_write_rejected");
        } catch (SeatMapCacheException exception) {
            count("load_failure");
            log.warn(
                    "Seat map cache write failed; returning database result. performanceTimeId={}, reason={}",
                    performanceTimeId,
                    exception.getMessage()
            );
        }
        return responses;
    }

    /**
     * Redis snapshot을 API 응답 DTO 목록으로 변환한다.
     * cache hit와 owner·joiner 결과의 응답 contract를 동일하게 유지한다.
     */
    private List<SeatResponse> toResponses(SeatMapCacheParts cacheParts) {
        return cacheParts.layoutEntries().stream()
                .map(layout -> {
                    SeatAvailabilityCacheEntry availability = cacheParts.availabilityEntries().get(layout.id());
                    if (availability == null) {
                        throw new SeatMapCacheException(
                                "Seat map cache read returned an incomplete seat set",
                                new IllegalStateException("Missing availability for seatId=" + layout.id())
                        );
                    }
                    return layout.toResponse(availability.seatStatus());
                })
                .toList();
    }

    /** DB projection 결과를 기존 좌석 응답 순서로 조합한다.
     * 동적 상태가 없는 좌석은 불완전한 DB 결과로 보고 즉시 실패시킨다. */
    private List<SeatResponse> toResponses(SeatMapDatabaseSnapshot databaseSnapshot) {
        java.util.Map<Long, SeatAvailabilityCacheEntry> availabilityById = databaseSnapshot.availabilityEntries().stream()
                .collect(java.util.stream.Collectors.toMap(SeatAvailabilityCacheEntry::seatId, entry -> entry));
        return databaseSnapshot.layoutEntries().stream()
                .map(layout -> {
                    SeatAvailabilityCacheEntry availability = availabilityById.get(layout.id());
                    if (availability == null) {
                        throw new IllegalStateException("Missing database availability for seatId=" + layout.id());
                    }
                    return layout.toResponse(availability.seatStatus());
                })
                .toList();
    }

    /**
     * Redis 장애·miss 요청의 DB 유입량을 회차별로 제한한다.
     * 한도를 넘은 요청은 DB connection을 얻기 전에 재시도 가능한 503으로 종료한다.
     */
    private List<SeatResponse> readDatabaseWithFallbackBudget(long performanceTimeId) {
        Semaphore permit = fallbackPermits.computeIfAbsent(
                performanceTimeId,
                ignored -> new Semaphore(properties.getFallbackMaxConcurrency(), true)
        );
        if (!permit.tryAcquire()) {
            count("fallback_rejected");
            throw new BusinessException(ReservationErrorCode.SEAT_MAP_FALLBACK_OVER_CAPACITY);
        }
        try {
            count("database_projection");
            return databaseReader.read(performanceTimeId);
        } finally {
            permit.release();
        }
    }

    /** split cache miss의 두 projection을 하나의 DB 읽기 예산으로 제한한다.
     * 같은 회차의 cold burst가 Hikari 연결을 모두 점유하지 않게 한다. */
    private SeatMapDatabaseSnapshot readDatabaseSnapshotWithFallbackBudget(long performanceTimeId) {
        Semaphore permit = fallbackPermits.computeIfAbsent(
                performanceTimeId,
                ignored -> new Semaphore(properties.getFallbackMaxConcurrency(), true)
        );
        if (!permit.tryAcquire()) {
            count("fallback_rejected");
            throw new BusinessException(ReservationErrorCode.SEAT_MAP_FALLBACK_OVER_CAPACITY);
        }
        try {
            count("database_projection");
            return databaseReader.readSplit(performanceTimeId);
        } finally {
            permit.release();
        }
    }

    /**
     * future에서 전달된 실패를 호출 경계의 unchecked 예외로 복원한다.
     * Error는 원래 종류를 유지하고 일반 checked 예외는 상태 예외로 감싼다.
     */
    private RuntimeException propagate(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Seat map single-flight failed", throwable);
    }

    /**
     * cache 비활성 경로의 DB projection을 수행하고 호출 횟수를 기록한다.
     * before·after 부하에서 실제 MySQL projection 횟수를 직접 비교할 수 있게 한다.
     */
    private List<SeatResponse> readDatabaseDirect(long performanceTimeId) {
        count("database_projection");
        return databaseReader.read(performanceTimeId);
    }

    /**
     * cache 경로별 관측 counter를 증가시킨다.
     * hit·miss·single-flight·fallback·load 상태를 동일한 metric 이름으로 집계한다.
     */
    private void count(String event) {
        meterRegistry.counter("imticket.seat-map-cache.events", "event", event).increment();
    }

    /**
     * 회차별 Redis 읽기 동시성을 장애 초기에는 1개로 시작하고, 성공한 뒤 설정값까지 확장한다.
     * 첫 Redis 연결 대기가 전체 요청으로 복제되는 현상을 차단한다.
     */
    private static final class CacheReadGate {

        private final int maximumConcurrency;
        private final Semaphore permits = new Semaphore(1, true);
        private final AtomicBoolean expanded = new AtomicBoolean();

        /**
         * 회차별 Redis 읽기 동시성 gate를 생성한다.
         * 첫 번째 성공적인 snapshot hit 뒤 설정된 최대 동시성으로 확장한다.
         */
        private CacheReadGate(int maximumConcurrency) {
            this.maximumConcurrency = maximumConcurrency;
        }

        /**
         * 현재 gate에 읽기 permit이 남아 있는지 확인한다.
         * permit이 없으면 호출자는 single-flight 또는 fallback 경로로 이동한다.
         */
        private boolean tryAcquire() {
            return permits.tryAcquire();
        }

        /**
         * snapshot hit 이후 높은 동시성으로 확장된 gate인지 확인한다.
         * 초기 miss gate는 요청 간에 재사용해 miss 세대의 Redis 읽기를 직렬화한다.
         */
        private boolean isExpanded() {
            return expanded.get();
        }

        /**
         * snapshot hit가 확인되면 Redis 읽기 동시성을 설정값까지 확장한다.
         * miss 경로에서는 장애 초기의 단일 읽기 제한을 유지한다.
         */
        private void markSuccessfulRead(boolean snapshotHit) {
            if (snapshotHit && expanded.compareAndSet(false, true)) {
                permits.release(maximumConcurrency - 1);
            }
        }

        /**
         * 사용한 Redis 읽기 permit을 반환한다.
         * 호출 경로의 예외 여부와 무관하게 gate의 용량을 복원한다.
         */
        private void release() {
            permits.release();
        }
    }
}
