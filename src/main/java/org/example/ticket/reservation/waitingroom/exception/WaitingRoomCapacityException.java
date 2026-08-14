package org.example.ticket.reservation.waitingroom.exception;

/** Waiting Room queue capacity가 초과됐을 때 admission service에 전달하는 예외다. */
public class WaitingRoomCapacityException extends RuntimeException {

    /**
     * queue full 결과를 domain service가 HTTP 429로 변환할 수 있게 한다.
     * 대기열 상태를 변경하지 못한 원인을 전용 예외 타입으로 전달한다.
     */
    public WaitingRoomCapacityException() {
        super("Waiting Room queue capacity has been reached");
    }
}
