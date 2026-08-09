package org.example.ticket.reservation.queue.domain;

import org.example.ticket.reservation.common.domain.ReservationIntentFingerprint;
import org.example.ticket.reservation.common.util.ReservationIntentFingerprintFactory;

import java.util.List;

/** Queue 요청의 동일성을 비교할 정렬 좌석 목록과 SHA-256 fingerprint다. */
public final class ReservationQueueRequestFingerprint {

    private final long performanceTimeId;
    private final List<Long> normalizedSeatIds;
    private final String requestHash;

    /**
     * 공통 factory가 만든 회차, 정렬 좌석과 hash를 Queue 값으로 보관한다.
     * 좌석 목록은 외부 변경을 막기 위해 복사한다.
     */
    private ReservationQueueRequestFingerprint(
            long performanceTimeId,
            List<Long> normalizedSeatIds,
            String requestHash
    ) {
        this.performanceTimeId = performanceTimeId;
        this.normalizedSeatIds = List.copyOf(normalizedSeatIds);
        this.requestHash = requestHash;
    }

    /**
     * Queue 요청을 공통 예약 의도 규칙으로 정규화한다.
     * Booking과 같은 schema와 SHA-256을 사용하는 Queue fingerprint를 반환한다.
     */
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

    /**
     * fingerprint가 가리키는 공연 회차 ID를 반환한다.
     * Queue key와 ticket payload 범위를 결정할 때 사용한다.
     */
    public long performanceTimeId() {
        return performanceTimeId;
    }

    /**
     * 중복 없이 오름차순으로 정규화된 좌석 ID를 반환한다.
     * Queue payload와 Worker의 예약 입력에 같은 순서를 제공한다.
     */
    public List<Long> normalizedSeatIds() {
        return normalizedSeatIds;
    }

    /**
     * canonical 예약 의도의 SHA-256 값을 반환한다.
     * 같은 멱등 키로 다른 좌석 요청이 들어왔는지 판단할 때 사용한다.
     */
    public String requestHash() {
        return requestHash;
    }

}
