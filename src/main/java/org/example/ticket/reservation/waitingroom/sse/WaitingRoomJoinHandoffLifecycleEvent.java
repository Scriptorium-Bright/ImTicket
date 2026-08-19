package org.example.ticket.reservation.waitingroom.sse;

import org.example.ticket.reservation.waitingroom.service.WaitingRoomJoinHandoffStatus;

import java.time.Instant;
import java.util.UUID;

/** 비동기 join 요청 상태 변경을 application instance 사이에 전달하는 event다. */
public record WaitingRoomJoinHandoffLifecycleEvent(
        long performanceTimeId,
        UUID requestId,
        long memberId,
        WaitingRoomJoinHandoffStatus status,
        UUID ticketId,
        String errorCode,
        boolean retryable,
        Instant occurredAt
) {
}
