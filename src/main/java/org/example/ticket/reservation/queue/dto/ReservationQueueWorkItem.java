package org.example.ticket.reservation.queue.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** 검증된 Stream payload와 Redis claim 식별자를 묶은 Worker 입력이다. */
public record ReservationQueueWorkItem(
        UUID ticketId,
        long performanceTimeId,
        String streamId,
        String ownerHash,
        UUID ownerToken,
        ReservationQueuePayload payload,
        long sequence,
        Instant enqueuedAt
) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    /**
     * Worker가 Redis claim과 DB 실행에 사용할 모든 식별자를 검증한다.
     * Decoder 이후 단계가 손상된 Stream field를 다시 해석하지 않게 한다.
     */
    public ReservationQueueWorkItem {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        if (performanceTimeId <= 0 || sequence <= 0) {
            throw new IllegalArgumentException("Worker item identifiers must be positive");
        }
        if (streamId == null || streamId.isBlank()) {
            throw new IllegalArgumentException("streamId must not be blank");
        }
        if (ownerHash == null || !SHA_256.matcher(ownerHash).matches()) {
            throw new IllegalArgumentException("ownerHash must be a lowercase SHA-256 value");
        }
        Objects.requireNonNull(ownerToken, "ownerToken must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(enqueuedAt, "enqueuedAt must not be null");
    }
}
