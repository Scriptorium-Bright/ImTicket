package org.example.ticket.reservation.common.domain;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Booking과 Queue가 공유하는 정규화된 예약 의도와 비교용 hash다. */
public record ReservationIntentFingerprint(
        String schemaVersion,
        long performanceTimeId,
        List<Long> normalizedSeatIds,
        String requestHash
) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    /**
     * schema, 회차, 정렬 좌석과 request hash의 불변식을 검증한다.
     * Booking과 Queue가 같은 예약 의도를 안전하게 비교할 수 있게 한다.
     */
    public ReservationIntentFingerprint {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("schemaVersion must not be blank");
        }
        if (performanceTimeId <= 0) {
            throw new IllegalArgumentException("performanceTimeId must be positive");
        }
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
        if (requestHash == null || !SHA_256.matcher(requestHash).matches()) {
            throw new IllegalArgumentException("requestHash must be a lowercase SHA-256 value");
        }
    }
}
