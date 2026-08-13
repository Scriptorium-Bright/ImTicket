package org.example.ticket.reservation.waitingroom.exception;

/** Redis command·script 실패를 Waiting Room storage 오류로 감싼다. */
public class WaitingRoomStorageException extends RuntimeException {

    /** Redis storage 원인을 보존한 오류를 생성한다.
     * 원인 예외를 포함해 운영 로그에서 script 실패를 추적할 수 있다. */
    public WaitingRoomStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Redis script가 예상하지 못한 결과를 반환했을 때 사용할 오류를 생성한다.
     * 반환 contract가 바뀐 경우 성공 상태로 오인하지 않게 한다. */
    public WaitingRoomStorageException(String message) {
        super(message);
    }
}
