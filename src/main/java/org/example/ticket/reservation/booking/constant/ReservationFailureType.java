package org.example.ticket.reservation.booking.constant;

/** 예약 claim 실행 실패가 DB 상태와 재호출 정책에 미치는 영향을 구분한다. */
public enum ReservationFailureType {

    RETRYABLE,
    FINAL,
    LEASE_GUARDED
}

