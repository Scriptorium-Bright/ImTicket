package org.example.ticket.reservation.queue.domain;

import org.example.ticket.reservation.shared.intent.ReservationIntentFingerprint;
import org.example.ticket.reservation.shared.intent.ReservationIntentFingerprintFactory;

import java.util.List;

/** Queue 요청의 동일성을 비교할 정렬 좌석 목록과 SHA-256 fingerprint다. */
public final class ReservationQueueRequestFingerprint {

    private final long performanceTimeId;
    private final List<Long> normalizedSeatIds;
    private final String requestHash;

    private ReservationQueueRequestFingerprint(
            long performanceTimeId,
            List<Long> normalizedSeatIds,
            String requestHash
    ) {
        this.performanceTimeId = performanceTimeId;
        this.normalizedSeatIds = List.copyOf(normalizedSeatIds);
        this.requestHash = requestHash;
    }

    public static ReservationQueueRequestFingerprint of(long performanceTimeId, List<Long> seatIds) {
        ReservationIntentFingerprint fingerprint = ReservationIntentFingerprintFactory.create(
                performanceTimeId,
                seatIds
        );
        return new ReservationQueueRequestFingerprint(
                fingerprint.performanceTimeId(),
                fingerprint.normalizedSeatIds(),
                fingerprint.requestHash()
        );
    }

    public long performanceTimeId() {
        return performanceTimeId;
    }

    public List<Long> normalizedSeatIds() {
        return normalizedSeatIds;
    }

    public String requestHash() {
        return requestHash;
    }

}
