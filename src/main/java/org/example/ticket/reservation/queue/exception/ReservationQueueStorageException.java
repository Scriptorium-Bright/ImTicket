package org.example.ticket.reservation.queue.exception;

/** Redis queue data나 script 결과가 계약과 맞지 않을 때 사용하는 예외다. */
public final class ReservationQueueStorageException extends RuntimeException {

    /**
     * Redis 데이터나 script 결과의 계약 위반 메시지를 보존한다.
     * Queue 서비스가 저장소 장애를 공통 API 오류로 변환할 수 있게 한다.
     */
    public ReservationQueueStorageException(String message) {
        super(message);
    }

    /**
     * 저장소 계약 위반 메시지와 원인 예외를 함께 보존한다.
     * Redis 변환 실패의 원인을 추적하면서 외부 오류 형식은 유지한다.
     */
    public ReservationQueueStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
