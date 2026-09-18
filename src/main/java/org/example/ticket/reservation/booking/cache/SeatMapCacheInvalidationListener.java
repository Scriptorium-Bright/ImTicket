package org.example.ticket.reservation.booking.cache;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** DB transaction commit 뒤에만 좌석 snapshot version 증가와 무효화를 수행한다. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeatMapCacheInvalidationListener {

    private final SeatMapCacheStore cacheStore;
    private final SeatMapCacheReader cacheReader;
    private final SeatMapDatabaseReader databaseReader;
    private final MeterRegistry meterRegistry;

    /**
     * transaction commit 뒤 대상 회차 version을 증가시키고 snapshot을 삭제한다.
     * commit 이전에 발생한 상태 변경은 listener 실행 대상이 되지 않는다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void invalidate(SeatMapInvalidationEvent event) {
        try {
            if (event.requiresFullRebuild()) {
                cacheStore.evict(event.performanceTimeId());
                count("invalidation");
            } else {
                boolean updated = cacheStore.updateAvailability(
                        event.performanceTimeId(),
                        databaseReader.readAvailability(event.performanceTimeId(), event.seatIds())
                );
                count(updated ? "availability_update" : "availability_update_skipped");
            }
            cacheReader.resetReadGateAfterInvalidation(event);
        } catch (RuntimeException exception) {
            count("invalidation_failure");
            log.warn(
                    "Seat map cache update failed; TTL remains the stale snapshot safeguard. performanceTimeId={}, reason={}",
                    event.performanceTimeId(),
                    exception.getMessage()
            );
        }
    }

    /**
     * invalidation 성공·실패를 Prometheus counter로 남긴다.
     * cache 장애가 예약 transaction 결과를 다시 실패시키지 않게 한다.
     */
    private void count(String event) {
        meterRegistry.counter("imticket.seat-map-cache.events", "event", event).increment();
    }
}
