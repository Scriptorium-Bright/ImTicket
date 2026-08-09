package org.example.ticket.reservation.queue.application;

import org.example.ticket.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** Queue API에서 사용하는 오류와 HTTP 상태를 정의한다. */
public enum ReservationQueueErrorCode implements ErrorCode {
    AUTHENTICATION_REQUIRED(
            HttpStatus.UNAUTHORIZED,
            "QUEUE_AUTHENTICATION_REQUIRED",
            "예약 대기열 인증이 필요합니다."
    ),
    INVALID_REQUEST(
            HttpStatus.BAD_REQUEST,
            "QUEUE_REQUEST_INVALID",
            "예약 대기열 요청 값이 올바르지 않습니다."
    ),
    IDEMPOTENCY_KEY_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "QUEUE_IDEMPOTENCY_KEY_REQUIRED",
            "Idempotency-Key가 필요합니다."
    ),
    IDEMPOTENCY_KEY_INVALID(
            HttpStatus.BAD_REQUEST,
            "QUEUE_IDEMPOTENCY_KEY_INVALID",
            "Idempotency-Key는 canonical UUID여야 합니다."
    ),
    IDEMPOTENCY_CONFLICT(
            HttpStatus.CONFLICT,
            "QUEUE_IDEMPOTENCY_CONFLICT",
            "같은 Idempotency-Key에 다른 예약 요청을 사용할 수 없습니다."
    ),
    ENQUEUE_IN_PROGRESS(
            HttpStatus.SERVICE_UNAVAILABLE,
            "QUEUE_ENQUEUE_IN_PROGRESS",
            "같은 예약 요청의 대기열 접수를 확인하고 있습니다."
    ),
    QUEUE_FULL(
            HttpStatus.TOO_MANY_REQUESTS,
            "RESERVATION_QUEUE_FULL",
            "예약 대기열이 가득 찼습니다. 잠시 후 다시 시도해 주세요."
    ),
    QUEUE_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "RESERVATION_QUEUE_UNAVAILABLE",
            "예약 대기열을 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."
    ),
    TICKET_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "QUEUE_TICKET_NOT_FOUND",
            "예약 대기열 ticket을 찾을 수 없습니다."
    ),
    TICKET_EXPIRED(
            HttpStatus.GONE,
            "QUEUE_TICKET_EXPIRED",
            "예약 대기열 ticket이 만료되었습니다."
    );

    private final HttpStatus status;
    private final String code;
    private final String message;

    ReservationQueueErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
