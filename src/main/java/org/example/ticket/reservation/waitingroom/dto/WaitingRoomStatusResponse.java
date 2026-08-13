package org.example.ticket.reservation.waitingroom.dto;

import org.example.ticket.reservation.waitingroom.domain.WaitingRoomTicketStatus;

import java.time.Instant;
import java.util.UUID;

/** Waiting Room join·status·cancel API가 공유하는 상태 응답 contract다. */
public record WaitingRoomStatusResponse(
        UUID ticketId,
        WaitingRoomTicketStatus status,
        Long position,
        long sequence,
        Instant waitingDeadline,
        Instant entryExpiresAt,
        String entryPass,
        long pollAfterMs
) {
}
