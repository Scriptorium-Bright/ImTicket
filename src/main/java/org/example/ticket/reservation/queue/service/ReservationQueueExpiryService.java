package org.example.ticket.reservation.queue.service;

import org.example.ticket.reservation.queue.config.ReservationQueueProperties;
import org.example.ticket.reservation.queue.repository.ReservationQueueExpiryIndex;
import org.example.ticket.reservation.queue.repository.ReservationQueueTicketStore;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Active performance의 due ticket을 제한된 batch로 만료시킨다. */
public final class ReservationQueueExpiryService {

    private final ReservationQueueExpiryIndex expiryIndex;
    private final ReservationQueueTicketStore ticketStore;
    private final ReservationQueueProperties properties;

    /**
     * 만료 대상 index, ticket 저장소와 batch 설정을 주입한다.
     * scheduler 호출마다 동일한 제한과 Redis 계약을 사용한다.
     */
    public ReservationQueueExpiryService(
            ReservationQueueExpiryIndex expiryIndex,
            ReservationQueueTicketStore ticketStore,
            ReservationQueueProperties properties
    ) {
        this.expiryIndex = Objects.requireNonNull(expiryIndex, "expiryIndex must not be null");
        this.ticketStore = Objects.requireNonNull(ticketStore, "ticketStore must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * 활성 회차의 due ticket을 batch 제한 안에서 만료 처리한다.
     * 실제 EXPIRED로 바뀐 ticket 수를 반환하고 stale 회차를 정리한다.
     */
    public int expireDue(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        int expired = 0;
        for (Long performanceTimeId : expiryIndex.activePerformanceTimeIds()) {
            for (UUID ticketId : expiryIndex.dueTicketIds(
                    performanceTimeId,
                    now,
                    properties.expiryBatchSize()
            )) {
                if (ticketStore.expireIfDue(performanceTimeId, ticketId, now)) {
                    expired++;
                }
            }
        }
        expiryIndex.removeStalePerformanceTimes(now);
        return expired;
    }
}
