package org.example.ticket.reservation.queue.util.scheduler;

import org.example.ticket.reservation.queue.service.ReservationQueueExpiryService;
import org.example.ticket.reservation.queue.exception.ReservationQueueStorageException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.util.Objects;

/** Queue가 활성화된 instance에서 deadline 만료 batch를 주기적으로 실행한다. */
@Slf4j
public final class ReservationQueueExpiryScheduler {

    private final ReservationQueueExpiryService expiryService;
    private final Clock clock;

    /**
     * 만료 서비스와 scan 기준 시각을 제공할 Clock을 주입한다.
     * scheduler 자체에는 Redis 상태 변경 규칙을 두지 않는다.
     */
    public ReservationQueueExpiryScheduler(ReservationQueueExpiryService expiryService, Clock clock) {
        this.expiryService = Objects.requireNonNull(expiryService, "expiryService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 설정된 간격마다 due ticket 만료 batch를 실행한다.
     * Redis 장애는 기록하고 다음 scheduler 실행에서 다시 시도한다.
     */
    @Scheduled(fixedDelayString = "${reservation.queue.expiry-scan-interval:1s}")
    public void expireDue() {
        try {
            int expired = expiryService.expireDue(clock.instant());
            if (expired > 0) {
                log.info("Expired reservation queue tickets: count={}", expired);
            }
        } catch (DataAccessException | ReservationQueueStorageException exception) {
            log.warn("Reservation queue expiry scan failed: {}", exception.getMessage());
        }
    }
}
