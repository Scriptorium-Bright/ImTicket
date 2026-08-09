package org.example.ticket.reservation.queue.infrastructure.scheduling;

import org.example.ticket.reservation.queue.application.ReservationQueueExpiryService;
import org.example.ticket.reservation.queue.application.port.ReservationQueueStorageException;

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

    public ReservationQueueExpiryScheduler(ReservationQueueExpiryService expiryService, Clock clock) {
        this.expiryService = Objects.requireNonNull(expiryService, "expiryService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

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
