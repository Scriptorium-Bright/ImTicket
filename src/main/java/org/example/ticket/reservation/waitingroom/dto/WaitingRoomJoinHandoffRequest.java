package org.example.ticket.reservation.waitingroom.dto;

import java.time.Instant;
import java.util.UUID;

/** Redis Stream에 기록할 비동기 join 요청이다. */
public record WaitingRoomJoinHandoffRequest(
        UUID requestId,
        UUID ticketId,
        long performanceTimeId,
        long memberId,
        Instant enqueuedAt,
        Instant waitingDeadline
) {
    /** 요청 식별자와 회차·회원·수락 시각의 기본 조건을 검증한다.
     * 유효하지 않은 값은 Redis Stream에 기록하지 않는다. */
    public WaitingRoomJoinHandoffRequest {
        if (requestId == null || ticketId == null || performanceTimeId <= 0 || memberId <= 0
                || enqueuedAt == null || waitingDeadline == null || waitingDeadline.isBefore(enqueuedAt)) {
            throw new IllegalArgumentException("invalid join handoff request");
        }
    }
}
