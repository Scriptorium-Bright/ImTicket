package org.example.ticket.reservation.queue.application.port;

/** Redis queue data나 script 결과가 계약과 맞지 않을 때 사용하는 예외다. */
public final class ReservationQueueStorageException extends RuntimeException {

    public ReservationQueueStorageException(String message) {
        super(message);
    }

    public ReservationQueueStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
