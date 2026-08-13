package org.example.ticket.reservation.waitingroom.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.performance.model.PerformanceTime;
import org.example.ticket.performance.repository.PerformanceTimeRepository;
import org.example.ticket.reservation.waitingroom.config.WaitingRoomProperties;
import org.example.ticket.reservation.waitingroom.service.WaitingRoomService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 활성 회차를 순회해 Waiting Room expiry와 promotion을 주기적으로 실행한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class WaitingRoomPromotionScheduler {

    private final PerformanceTimeRepository performanceTimeRepository;
    private final WaitingRoomProperties properties;
    private final WaitingRoomService waitingRoomService;

    /** Waiting Room이 활성화된 경우 회차별 admission batch를 실행한다.
     * 한 회차의 오류가 scheduler 전체 주기를 중단하지 않게 격리한다. */
    @Scheduled(fixedDelayString = "${reservation.waiting-room.promotion-interval:1s}")
    public void promoteActivePerformanceTimes() {
        if (!properties.isEnabled()) {
            return;
        }
        for (PerformanceTime performanceTime : performanceTimeRepository.findAll()) {
            Long performanceTimeId = performanceTime.getId();
            if (performanceTimeId == null || !waitingRoomService.requiresWaitingRoom(performanceTimeId)) {
                continue;
            }
            try {
                waitingRoomService.promote(performanceTimeId);
            } catch (RuntimeException exception) {
                log.warn("Waiting Room promotion failed: performanceTimeId={}", performanceTimeId, exception);
            }
        }
    }
}
