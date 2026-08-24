package org.example.ticket.reservation.waitingroom.scheduler;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.reservation.waitingroom.config.WaitingRoomProperties;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 활성 회차를 순회해 Waiting Room expiry와 promotion을 주기적으로 실행한다. */
@Slf4j
@Component
@ConditionalOnProperty(name = "ticket.application.role", havingValue = "waiting-room")
@RequiredArgsConstructor
public class WaitingRoomPromotionScheduler {

    private final WaitingRoomProperties properties;
    private final WaitingRoomService waitingRoomService;
    private final MeterRegistry meterRegistry;

    /** Waiting Room이 활성화된 경우 회차별 admission batch를 실행한다.
     * 한 회차의 오류가 scheduler 전체 주기를 중단하지 않게 격리한다. */
    @Scheduled(fixedRateString = "${reservation.waiting-room.promotion-interval:1s}")
    public void promoteActivePerformanceTimes() {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            meterRegistry.counter("imticket.waiting-room.scheduler.runs").increment();
            if (!properties.isEnabled()) {
                return;
            }
            for (Long performanceTimeId : properties.getEnabledPerformanceTimeIds()) {
                if (performanceTimeId == null || !waitingRoomService.requiresWaitingRoom(performanceTimeId)) {
                    continue;
                }
                try {
                    waitingRoomService.promote(performanceTimeId);
                } catch (RuntimeException exception) {
                    meterRegistry.counter("imticket.waiting-room.scheduler.failures").increment();
                    log.warn("Waiting Room promotion failed: performanceTimeId={}", performanceTimeId, exception);
                }
            }
        } finally {
            sample.stop(meterRegistry.timer("imticket.waiting-room.scheduler.duration"));
        }
    }
}
