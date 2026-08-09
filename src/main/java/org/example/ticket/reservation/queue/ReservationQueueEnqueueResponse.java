package org.example.ticket.reservation.queue;

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
