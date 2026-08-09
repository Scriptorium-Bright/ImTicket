package org.example.ticket.reservation.queue;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Redis queue admission에 필요한 정규화된 입력이다. */
public record ReservationQueueAdmissionCommand(
        long performanceTimeId,
        UUID ticketId,
        String ownerHash,
        String idempotencyKeyHash,
        String requestHash,
        List<Long> normalizedSeatIds,
        Instant enqueuedAt
) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public ReservationQueueAdmissionCommand {
        if (performanceTimeId <= 0) {
            throw new IllegalArgumentException("performanceTimeId must be positive");
        }
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        requireSha256(ownerHash, "ownerHash");
        requireSha256(idempotencyKeyHash, "idempotencyKeyHash");
        requireSha256(requestHash, "requestHash");
        Objects.requireNonNull(normalizedSeatIds, "normalizedSeatIds must not be null");
        if (normalizedSeatIds.isEmpty()) {
            throw new IllegalArgumentException("normalizedSeatIds must not be empty");
        }
        Long previous = null;
        for (Long seatId : normalizedSeatIds) {
            if (seatId == null || seatId <= 0) {
                throw new IllegalArgumentException("normalizedSeatIds must contain positive values");
            }
            if (previous != null && seatId <= previous) {
                throw new IllegalArgumentException("normalizedSeatIds must be sorted and unique");
            }
            previous = seatId;
        }
        normalizedSeatIds = List.copyOf(normalizedSeatIds);
        Objects.requireNonNull(enqueuedAt, "enqueuedAt must not be null");
    }

    public String serializedSeatIds() {
        return normalizedSeatIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
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
