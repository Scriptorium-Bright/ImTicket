package org.example.ticket.reservation.queue.dto;

import org.example.ticket.reservation.common.value.ReservationIdempotencyKey;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Queue ticket과 Stream이 Worker에 전달하는 versioned 예약 처리 입력이다. */
public record ReservationQueuePayload(
        int schemaVersion,
        long memberId,
        ReservationIdempotencyKey idempotencyKey,
        String requestHash,
        List<Long> normalizedSeatIds
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    /**
     * Worker payload schema와 예약 식별 값의 불변식을 검증한다.
     * 손상되거나 지원하지 않는 Stream payload가 DB 처리로 넘어가는 것을 막는다.
     */
    public ReservationQueuePayload {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported queue payload schemaVersion: " + schemaVersion);
        }
        if (memberId <= 0) {
            throw new IllegalArgumentException("memberId must be positive");
        }
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        if (requestHash == null || !SHA_256.matcher(requestHash).matches()) {
            throw new IllegalArgumentException("requestHash must be a lowercase SHA-256 value");
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
    }

    /**
     * 현재 schema version으로 Worker payload를 만든다.
     * Queue 서비스가 version 숫자를 직접 반복하지 않게 한다.
     */
    public static ReservationQueuePayload current(
            long memberId,
            ReservationIdempotencyKey idempotencyKey,
            String requestHash,
            List<Long> normalizedSeatIds
    ) {
        return new ReservationQueuePayload(
                CURRENT_SCHEMA_VERSION,
                memberId,
                idempotencyKey,
                requestHash,
                normalizedSeatIds
        );
    }

    /**
     * 정렬 좌석 ID를 쉼표로 구분한 안정적인 문자열로 변환한다.
     * Ticket Hash와 Stream entry가 같은 좌석 표현을 저장하게 한다.
     */
    public String serializedSeatIds() {
        return normalizedSeatIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }
}
