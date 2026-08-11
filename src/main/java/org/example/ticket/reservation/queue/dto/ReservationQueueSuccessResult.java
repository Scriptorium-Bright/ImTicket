package org.example.ticket.reservation.queue.dto;

import java.time.LocalDateTime;
import java.util.Objects;

/** Queue 성공 ticket이 Client와 recovery에 제공하는 versioned 예약 결과다. */
public record ReservationQueueSuccessResult(
        int schemaVersion,
        long reservationId,
        int totalPrice,
        String orderUid,
        LocalDateTime expiredTime
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * 성공 결과 schema와 결제 준비에 필요한 예약 식별자를 검증한다.
     * Redis terminal Hash에 손상된 예약 snapshot이 저장되는 것을 막는다.
     */
    public ReservationQueueSuccessResult {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported Queue success schemaVersion: " + schemaVersion);
        }
        if (reservationId <= 0 || totalPrice < 0) {
            throw new IllegalArgumentException("Queue success result numbers are invalid");
        }
        if (orderUid == null || orderUid.isBlank()) {
            throw new IllegalArgumentException("orderUid must not be blank");
        }
        Objects.requireNonNull(expiredTime, "expiredTime must not be null");
    }
}
