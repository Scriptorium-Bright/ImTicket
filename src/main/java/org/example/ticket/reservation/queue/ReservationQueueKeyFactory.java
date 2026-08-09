package org.example.ticket.reservation.queue;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Queue Redis key를 한곳에서 생성해 hash tag와 식별자 노출 규칙을 유지한다. */
public final class ReservationQueueKeyFactory {

    private static final String PREFIX = "reservation:queue:";
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public String stream(long performanceTimeId) {
        return scoped(performanceTimeId, "stream");
    }

    public String admitted(long performanceTimeId) {
        return scoped(performanceTimeId, "admitted");
    }

    public String waiting(long performanceTimeId) {
        return scoped(performanceTimeId, "waiting");
    }

    public String deadline(long performanceTimeId) {
        return scoped(performanceTimeId, "deadline");
    }

    public String sequence(long performanceTimeId) {
        return scoped(performanceTimeId, "sequence");
    }

    public String ticket(long performanceTimeId, UUID ticketId) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        return scoped(performanceTimeId, "ticket:" + ticketId);
    }

    public String idempotency(String ownerHash, String idempotencyKeyHash) {
        requireSha256(ownerHash, "ownerHash");
        requireSha256(idempotencyKeyHash, "idempotencyKeyHash");
        return PREFIX + "idempotency:" + ownerHash + ":" + idempotencyKeyHash;
    }

    public String activePerformanceTimes() {
        return PREFIX + "active-performance-times";
    }

    private String scoped(long performanceTimeId, String suffix) {
        if (performanceTimeId <= 0) {
            throw new IllegalArgumentException("performanceTimeId must be positive");
        }
        return PREFIX + "{" + performanceTimeId + "}:" + suffix;
    }

    private void requireSha256(String value, String name) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 value");
        }
    }
}
