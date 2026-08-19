package org.example.ticket.reservation.waitingroom.dto;

import org.example.ticket.reservation.waitingroom.service.WaitingRoomJoinHandoffStatus;

import java.util.UUID;

/** request SSE가 전달하는 비동기 join 실패 응답이다. */
public record WaitingRoomJoinHandoffFailureResponse(
        UUID requestId,
        WaitingRoomJoinHandoffStatus status,
        String errorCode,
        boolean retryable
) {
}
