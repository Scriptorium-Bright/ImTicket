package org.example.ticket.reservation.booking.service;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reservation Application에서 만료 예약 정리 작업을 주기적으로 실행한다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "ticket.application.role",
        havingValue = "reservation",
        matchIfMissing = true
)
public class ReservationExpirationScheduler {

    private static final long EXPIRED_SCHEDULING_TIME = 30000;

    private final ReservationService reservationService;

    /** 여러 Reservation Application 인스턴스의 중복 정리를 ShedLock으로 제어한다. */
    @Scheduled(fixedDelay = EXPIRED_SCHEDULING_TIME)
    @SchedulerLock(name = "cleanupExpiredReservation", lockAtMostFor = "PT6M")
    public void cleanupExpiredReservation() {
        reservationService.cleanupExpiredReservation();
    }
}
