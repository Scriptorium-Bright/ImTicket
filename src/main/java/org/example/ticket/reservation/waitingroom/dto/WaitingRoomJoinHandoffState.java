package org.example.ticket.reservation.waitingroom.dto;

import org.example.ticket.reservation.waitingroom.service.WaitingRoomJoinHandoffStatus;

import java.time.Instant;
import java.util.UUID;

/** Redis에 보존된 비동기 join 요청 상태다. */
public record WaitingRoomJoinHandoffState(
        UUID requestId,
        long performanceTimeId,
        long memberId,
        WaitingRoomJoinHandoffStatus status,
        Instant enqueuedAt,
        UUID ticketId,
        String errorCode,
        boolean retryable
) {
}
