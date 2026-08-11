package org.example.ticket.reservation.queue.util.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.example.ticket.reservation.queue.service.ReservationQueueMaintenanceService;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.util.Objects;

/** 설정된 주기마다 Queue maintenance 한 tick을 실행한다. */
@Slf4j
public final class ReservationQueueMaintenanceScheduler {

    private final ReservationQueueMaintenanceService maintenanceService;
    private final Clock clock;

    /**
     * Maintenance service와 실행 기준 시각을 제공하는 Clock을 연결한다.
     * Scheduler는 상태 판단을 수행하지 않고 한 tick 호출과 실패 기록만 담당한다.
     */
    public ReservationQueueMaintenanceScheduler(
            ReservationQueueMaintenanceService maintenanceService,
            Clock clock
    ) {
        this.maintenanceService = Objects.requireNonNull(
                maintenanceService,
                "maintenanceService must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 설정된 maintenance 간격마다 현재 시각 기준 복구와 cleanup을 실행한다.
     * Redis 일시 오류는 기록하고 다음 scheduler tick에서 다시 시도한다.
     */
    @Scheduled(fixedDelayString = "${reservation.queue.maintenance.interval:5s}")
    public void maintain() {
        try {
            maintenanceService.runOnce(clock.instant());
        } catch (RuntimeException exception) {
            log.warn("Reservation Queue maintenance failed: {}", exception.getMessage());
        }
    }
}
