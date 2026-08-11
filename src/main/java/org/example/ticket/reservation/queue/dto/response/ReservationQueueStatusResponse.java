package org.example.ticket.reservation.queue.dto.response;

import org.example.ticket.reservation.queue.constant.ReservationQueueStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 소유자가 조회하는 queue ticket 상태다. */
public record ReservationQueueStatusResponse(
        long performanceTimeId,
        UUID ticketId,
        ReservationQueueStatus status,
        Long position,
        Instant enqueuedAt,
        Instant deadlineAt,
        long pollAfterMs
) {

    /**
     * Queue 상태 응답의 식별자, 시각과 선택적 순번을 검증한다.
     * 클라이언트가 현재 상태와 다음 조회 시점을 일관되게 해석하게 한다.
     */
    public ReservationQueueStatusResponse {
        if (performanceTimeId <= 0 || pollAfterMs <= 0) {
            throw new IllegalArgumentException("Queue status response numbers must be positive");
        }
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(enqueuedAt, "enqueuedAt must not be null");
        Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
        if (position != null && position <= 0) {
            throw new IllegalArgumentException("position must be positive");
        }
    }
}
