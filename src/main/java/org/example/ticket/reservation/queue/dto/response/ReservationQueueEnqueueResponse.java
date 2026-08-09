package org.example.ticket.reservation.queue.dto.response;

import org.example.ticket.reservation.queue.domain.ReservationQueueStatus;

import java.util.Objects;
import java.util.UUID;

/** Queue 접수 직후 client에 전달할 polling 정보다. */
public record ReservationQueueEnqueueResponse(
        long performanceTimeId,
        UUID ticketId,
        ReservationQueueStatus status,
        boolean replayed,
        long pollAfterMs,
        String statusUrl
) {

    /**
     * Queue 접수 응답의 ticket, polling 간격과 상태 URL을 검증한다.
     * 클라이언트가 즉시 안전한 polling을 시작할 수 있는 값만 허용한다.
     */
    public ReservationQueueEnqueueResponse {
        if (performanceTimeId <= 0 || pollAfterMs <= 0) {
            throw new IllegalArgumentException("Queue response numbers must be positive");
        }
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (statusUrl == null || statusUrl.isBlank()) {
            throw new IllegalArgumentException("statusUrl must not be blank");
        }
    }
}
