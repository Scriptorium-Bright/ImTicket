package org.example.ticket.reservation.booking.cache;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.reservation.booking.dto.response.SeatResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** 좌석 조회의 cache hit·miss·fallback 정책을 조정한다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SeatMapCacheReader {

    private final SeatMapCacheFeaturePolicy featurePolicy;
    private final SeatMapCacheProperties properties;
    private final SeatMapCacheStore cacheStore;
    private final SeatMapDatabaseReader databaseReader;
    private final MeterRegistry meterRegistry;

    /**
     * feature flag에 따라 cache hit·miss·DB fallback 경로를 선택한다.
     * cache 오류는 좌석 조회 자체의 실패로 전파하지 않고 DB 경로로 전환한다.
     */
    public List<SeatResponse> read(long performanceTimeId) {
        if (!featurePolicy.appliesTo(performanceTimeId)) {
            count("disabled");
            return databaseReader.read(performanceTimeId);
        }

        try {
            Optional<List<SeatMapCacheEntry>> cached = cacheStore.get(performanceTimeId);
            if (cached.isPresent()) {
                count("hit");
                return cached.get().stream()
                        .map(SeatMapCacheEntry::toResponse)
                        .toList();
            }
            count("miss");
        } catch (SeatMapCacheException exception) {
            count("fallback");
            log.warn(
                    "Seat map cache read failed; falling back to database. performanceTimeId={}, reason={}",
                    performanceTimeId,
                    exception.getMessage()
            );
        }

        List<SeatResponse> responses = databaseReader.read(performanceTimeId);
        try {
            cacheStore.put(
                    performanceTimeId,
                    responses.stream().map(SeatMapCacheEntry::from).toList(),
                    properties.getTtl()
            );
            count("load");
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
     * cache 경로별 관측 counter를 증가시킨다.
     * hit·miss·fallback·load 상태를 동일한 metric 이름으로 집계한다.
     */
    private void count(String event) {
        meterRegistry.counter("imticket.seat-map-cache.events", "event", event).increment();
    }
}
