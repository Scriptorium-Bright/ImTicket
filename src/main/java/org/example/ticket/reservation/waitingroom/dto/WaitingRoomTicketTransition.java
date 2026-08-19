package org.example.ticket.reservation.waitingroom.dto;

import org.example.ticket.reservation.waitingroom.domain.WaitingRoomTicketStatus;

import java.util.Objects;
import java.util.UUID;

/** promotion 결과를 lifecycle 전달에 필요한 최소 ticket 전이 정보로 표현한다. */
public record WaitingRoomTicketTransition(
        UUID ticketId,
        WaitingRoomTicketStatus status
) {
    public WaitingRoomTicketTransition {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
