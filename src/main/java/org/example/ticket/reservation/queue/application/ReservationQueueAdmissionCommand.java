package org.example.ticket.reservation.queue.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Redis queue admission에 필요한 정규화된 입력이다. */
public record ReservationQueueAdmissionCommand(
        long performanceTimeId,
        UUID ticketId,
        String ownerHash,
        ReservationQueuePayload payload,
        Instant enqueuedAt
) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public ReservationQueueAdmissionCommand {
        if (performanceTimeId <= 0) {
            throw new IllegalArgumentException("performanceTimeId must be positive");
        }
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        requireSha256(ownerHash, "ownerHash");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(enqueuedAt, "enqueuedAt must not be null");
    }

    public Instant deadline(ReservationQueueProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        return enqueuedAt.plus(properties.maxWait());
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
    }
}
