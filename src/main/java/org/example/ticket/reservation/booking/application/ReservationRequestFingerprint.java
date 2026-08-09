package org.example.ticket.reservation.booking.application;

import java.util.List;

/** 정규화한 좌석 목록과 해당 예약 의도의 비교용 fingerprint를 함께 전달하는 내부 값이다. */
public record ReservationRequestFingerprint(String requestHash, List<Long> normalizedSeatIds) {

    public ReservationRequestFingerprint {
        normalizedSeatIds = List.copyOf(normalizedSeatIds);
    }
}
