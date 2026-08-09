package org.example.ticket.reservation.queue;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Queue ticket 조회와 단일 ticket 만료 전이를 제공한다. */
public interface ReservationQueueTicketStore {

    Optional<ReservationQueueTicketSnapshot> find(long performanceTimeId, UUID ticketId);

    boolean expireIfDue(long performanceTimeId, UUID ticketId, Instant now);
}
