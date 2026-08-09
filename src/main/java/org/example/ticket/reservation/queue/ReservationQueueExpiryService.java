package org.example.ticket.reservation.queue;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Active performance의 due ticket을 제한된 batch로 만료시킨다. */
public final class ReservationQueueExpiryService {

    private final ReservationQueueExpiryIndex expiryIndex;
    private final ReservationQueueTicketStore ticketStore;
    private final ReservationQueueProperties properties;

    public ReservationQueueExpiryService(
            ReservationQueueExpiryIndex expiryIndex,
            ReservationQueueTicketStore ticketStore,
            ReservationQueueProperties properties
    ) {
        this.expiryIndex = Objects.requireNonNull(expiryIndex, "expiryIndex must not be null");
        this.ticketStore = Objects.requireNonNull(ticketStore, "ticketStore must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

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
