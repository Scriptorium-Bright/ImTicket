package org.example.ticket.reservation.booking.support;

/** 멱등성 응답 snapshot을 직렬화·역직렬화할 때 발생한 예외를 표현한다. */
public class ReservationSnapshotException extends RuntimeException {

    /** 원인 없이 snapshot 오류 메시지만 담아 예외를 생성한다. */
    public ReservationSnapshotException(String message) {
        super(message);
    }

    /** 원인 예외와 함께 snapshot 오류를 생성한다. */
    public ReservationSnapshotException(String message, Throwable cause) {
        super(message, cause);
    }
}
