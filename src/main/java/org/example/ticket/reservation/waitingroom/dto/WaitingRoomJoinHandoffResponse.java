package org.example.ticket.reservation.waitingroom.dto;

import org.example.ticket.reservation.waitingroom.service.WaitingRoomJoinHandoffStatus;

import java.util.UUID;

/** 202 Accepted join 응답 contract다. */
public record WaitingRoomJoinHandoffResponse(
        UUID requestId,
        WaitingRoomJoinHandoffStatus status,
        long retryAfterMs,
        UUID ticketId
) {
    /** 기존 request 상태 응답을 위한 호환 생성자다.
     * 아직 ticket ID를 포함하지 않는 호출자는 null을 전달한다. */
    public WaitingRoomJoinHandoffResponse(
            UUID requestId,
            WaitingRoomJoinHandoffStatus status,
            long retryAfterMs
    ) {
        this(requestId, status, retryAfterMs, null);
    }
}
