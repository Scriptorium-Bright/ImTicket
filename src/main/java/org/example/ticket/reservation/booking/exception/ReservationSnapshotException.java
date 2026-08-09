package org.example.ticket.reservation.booking.exception;

/** 멱등성 응답 snapshot을 직렬화하거나 역직렬화할 때 발생한 예외를 표현한다. */
public class ReservationSnapshotException extends RuntimeException {

    /**
     * response snapshot 오류 메시지만 담은 예외를 생성한다.
     * 직렬화 계약 위반을 예약 처리 계층에 전달할 때 사용한다.
     */
    public ReservationSnapshotException(String message) {
        super(message);
    }

    /**
     * response snapshot 오류 메시지와 원인 예외를 함께 보존한다.
     * JSON 변환 실패의 상세 원인을 추적할 수 있게 한다.
     */
    public ReservationSnapshotException(String message, Throwable cause) {
        super(message, cause);
    }
}
