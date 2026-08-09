package org.example.ticket.reservation.booking.dto;

import java.util.List;

/** 정규화한 좌석 목록과 해당 예약 의도의 비교용 fingerprint를 함께 전달하는 내부 값이다. */
public record ReservationRequestFingerprint(String requestHash, List<Long> normalizedSeatIds) {

    /**
     * 계산된 요청 hash와 정렬 좌석 목록을 불변 값으로 보관한다.
     * 외부 목록 변경이 멱등성 비교 결과에 영향을 주지 않도록 복사한다.
     */
    public ReservationRequestFingerprint {
        normalizedSeatIds = List.copyOf(normalizedSeatIds);
    }
}
