package org.example.ticket.reservation.waitingroom.sse;

import org.example.ticket.reservation.waitingroom.domain.WaitingRoomTicketStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Redis lifecycle 전이 뒤 instance 간 전달에 사용하는 최소 event payload다. */
public record WaitingRoomTicketLifecycleEvent(
        long performanceTimeId,
        UUID ticketId,
        WaitingRoomTicketStatus status,
        Instant occurredAt
) {
    /** lifecycle event의 회차·ticket·상태·발생 시각을 검증한다.
     * Redis Pub/Sub payload가 유효한 식별자를 갖도록 보장한다. */
    public WaitingRoomTicketLifecycleEvent {
        if (performanceTimeId <= 0) {
            throw new IllegalArgumentException("performanceTimeId must be positive");
        }
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
