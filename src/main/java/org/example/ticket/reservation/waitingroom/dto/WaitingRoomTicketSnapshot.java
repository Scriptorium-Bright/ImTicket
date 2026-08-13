package org.example.ticket.reservation.waitingroom.dto;

import org.example.ticket.reservation.waitingroom.domain.WaitingRoomTicketStatus;

import java.time.Instant;
import java.util.UUID;

/** Redis ticket Hash를 API와 service가 사용하는 불변 snapshot으로 표현한다. */
public record WaitingRoomTicketSnapshot(
        UUID ticketId,
        long memberId,
        long performanceTimeId,
        WaitingRoomTicketStatus status,
        long sequence,
        Instant enqueuedAt,
        Instant waitingDeadline,
        Instant entryExpiresAt
) {
}
