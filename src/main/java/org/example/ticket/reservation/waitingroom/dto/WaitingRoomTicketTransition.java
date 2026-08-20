package org.example.ticket.reservation.waitingroom.dto;

import org.example.ticket.reservation.waitingroom.domain.WaitingRoomTicketStatus;

import java.util.Objects;
import java.util.UUID;

/** promotion 결과를 lifecycle 전달에 필요한 최소 ticket 전이 정보로 표현한다. */
public record WaitingRoomTicketTransition(
        UUID ticketId,
        WaitingRoomTicketStatus status
) {
    /**
     * 티켓 식별자와 상태를 검증해 유효한 상태 전이 정보를 생성한다.
     * 두 값이 없으면 생성하지 않는다.
     */
    public WaitingRoomTicketTransition {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
