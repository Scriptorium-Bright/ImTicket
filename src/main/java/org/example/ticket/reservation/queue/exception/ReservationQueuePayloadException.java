package org.example.ticket.reservation.queue.exception;

import java.util.Objects;

/** Worker가 DB 진입 전에 발견한 Stream payload 계약 위반이다. */
public final class ReservationQueuePayloadException extends RuntimeException {

    public enum Reason {
        UNSUPPORTED_SCHEMA,
        INVALID_FIELD,
        FINGERPRINT_MISMATCH
    }

    private final Reason reason;

    /**
     * 외부에 노출 가능한 안정적인 분류와 내부 진단 메시지를 보관한다.
     * Terminal 처리에서는 reason만 사용해 원본 예외 메시지 노출을 막는다.
     */
    public ReservationQueuePayloadException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    /**
     * payload 거절 원인의 안정적인 분류를 반환한다.
     * Processor가 unsupported schema와 손상 field를 공개 code로 변환할 때 사용한다.
     */
    public Reason reason() {
        return reason;
    }
}
