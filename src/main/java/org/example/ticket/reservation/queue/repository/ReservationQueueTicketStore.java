package org.example.ticket.reservation.queue.repository;

import org.example.ticket.reservation.queue.dto.ReservationQueueTicketSnapshot;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Queue ticket 조회와 단일 ticket 만료 전이를 제공한다. */
public interface ReservationQueueTicketStore {

    /**
     * 회차와 ticket ID로 현재 Queue snapshot을 조회한다.
     * ticket이 없으면 빈 Optional을 반환한다.
     */
    Optional<ReservationQueueTicketSnapshot> find(long performanceTimeId, UUID ticketId);

    /**
     * deadline이 지난 대기 ticket을 원자적으로 만료 처리한다.
     * 실제 상태가 EXPIRED로 바뀐 경우에만 true를 반환한다.
     */
    boolean expireIfDue(long performanceTimeId, UUID ticketId, Instant now);
}
